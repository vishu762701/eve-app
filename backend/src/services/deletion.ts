import { AuthUser, Env } from '../types';
import { prepareAdminAuditLog } from '../util/auditLog';
import { extractStorageAvatarKey } from '../util/avatarMedia';

export interface CascadeDeleteResult {
  deleted: boolean;
  avatarKeys: string[];
  syllabusPaths: string[];
}

function extractExamSyllabusRef(rawUrl: string): string {
  const value = String(rawUrl || '').trim();
  if (!value) return '';
  if (value.startsWith('tg:')) return value;
  if (value.startsWith('r2://')) return value.slice('r2://'.length).replace(/^\/+/, '');
  if (value.includes('/syllabus')) {
    try {
      const parsed = new URL(value, 'https://eve.local');
      const keyParam = parsed.searchParams.get('k');
      if (keyParam && (keyParam.startsWith('tg:') || keyParam.startsWith('syllabi/'))) {
        return keyParam;
      }
    } catch {}
  }
  return '';
}

async function getExistingTableNames(db: Env['DB']): Promise<Set<string>> {
  const names = new Set<string>();
  try {
    const { results } = await db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
      .all<{ name: string }>();
    for (const row of results || []) {
      if (row?.name) names.add(String(row.name).trim());
    }
  } catch {
    // Fall back to empty set; core tables are still included explicitly.
  }
  return names;
}

async function collectExamTreeRows(db: Env['DB'], rootExamId: string): Promise<Array<{
  id: string;
  avatar_url?: string;
  image_url?: string;
  syllabus_url?: string;
  publish_mode?: string;
}>> {
  try {
    const { results } = await db.prepare(
      `WITH RECURSIVE tree(id) AS (
        SELECT id FROM exams WHERE id = ?1
        UNION ALL
        SELECT e.id FROM exams e JOIN tree t ON e.parent_exam_id = t.id
      )
      SELECT e.* FROM exams e JOIN tree t ON e.id = t.id`
    ).bind(rootExamId).all<any>();
    return results || [];
  } catch {
    const root = await db.prepare('SELECT * FROM exams WHERE id = ?1').bind(rootExamId).first<any>();
    return root ? [root] : [];
  }
}

/**
 * Counts linked sub-exams, tests, questions, attempts, and sessions for informational delete
 * confirmations. Live and attempted records are never blocked from authorized admin deletion.
 */
export async function deletionInfo(db: Env['DB'], id: string, kind: 'exam' | 'test') {
  if (kind === 'test') {
    const testRow = await db
      .prepare('SELECT id, exam_id, status, question_count FROM generated_tests WHERE id = ?1')
      .bind(id)
      .first<{ id: string; exam_id: string; status?: string; question_count?: number }>();
    const match = "exam_id = ?1 OR substr(exam_id, -length(?1) - 2) = '__' || ?1";
    const row = await db.prepare(`SELECT
      (SELECT COUNT(*) FROM attempts WHERE ${match}) AS attempts,
      (SELECT COUNT(*) FROM attempt_sessions WHERE ${match.replaceAll('exam_id', 'exam_key')}) AS sessions,
      0 AS subExams,
      (SELECT COUNT(*) FROM generation_jobs WHERE test_id = ?1 AND status = 'running') AS generationJobs`)
      .bind(id).first<{ attempts: number; sessions: number; subExams: number; generationJobs: number }>();
    const counts = row || { attempts: 0, sessions: 0, subExams: 0, generationJobs: 0 };
    const status = String(testRow?.status || '').toLowerCase();
    const isLive = status === 'live' || status === 'published';
    return {
      ...counts,
      attemptCount: counts.attempts,
      sessionCount: counts.sessions,
      testCount: testRow ? 1 : 0,
      questionCount: Number(testRow?.question_count || 0),
      isLive,
      canDelete: true,
      code: null,
      message: null,
      safeAction: null,
    };
  }

  const treeRows = await collectExamTreeRows(db, id);
  const examIds = treeRows.map((r) => String(r.id)).filter(Boolean);
  const effectiveIds = examIds.length > 0 ? examIds : [id];
  const idsJson = JSON.stringify(effectiveIds);
  const examMatch = "EXISTS (SELECT 1 FROM json_each(?1) t WHERE exam_id = t.value OR substr(exam_id, 1, length(t.value) + 2) = t.value || '__')";
  const sessionMatch = "EXISTS (SELECT 1 FROM json_each(?1) t WHERE exam_key = t.value OR substr(exam_key, 1, length(t.value) + 2) = t.value || '__')";

  const row = await db.prepare(`SELECT
    (SELECT COUNT(*) FROM attempts WHERE ${examMatch}) AS attempts,
    (SELECT COUNT(*) FROM attempt_sessions WHERE ${sessionMatch}) AS sessions,
    (SELECT COUNT(*) FROM exams WHERE parent_exam_id IN (SELECT value FROM json_each(?1))) AS subExams,
    (SELECT COUNT(*) FROM generation_jobs WHERE exam_id IN (SELECT value FROM json_each(?1)) AND status = 'running') AS generationJobs,
    (SELECT COUNT(*) FROM generated_tests WHERE exam_id IN (SELECT value FROM json_each(?1))) AS testCount,
    (SELECT COUNT(*) FROM questions WHERE exam_id IN (SELECT value FROM json_each(?1))) AS questionCount`)
    .bind(idsJson)
    .first<{
      attempts: number;
      sessions: number;
      subExams: number;
      generationJobs: number;
      testCount: number;
      questionCount: number;
    }>();

  const counts = row || {
    attempts: 0,
    sessions: 0,
    subExams: 0,
    generationJobs: 0,
    testCount: 0,
    questionCount: 0,
  };
  const rootRow = treeRows.find((r) => String(r.id) === id);
  const rootMode = String(rootRow?.publish_mode || 'live').toLowerCase();
  const isLive = rootMode !== 'paused' && rootMode !== 'draft';

  return {
    attempts: counts.attempts,
    sessions: counts.sessions,
    subExams: counts.subExams,
    generationJobs: counts.generationJobs,
    attemptCount: counts.attempts,
    sessionCount: counts.sessions,
    testCount: counts.testCount,
    questionCount: counts.questionCount,
    isLive,
    canDelete: true,
    code: null,
    message: null,
    safeAction: null,
  };
}

/**
 * Permanently and atomically cascade-deletes any exam/sub-exam or specific mock test
 * regardless of live/paused/draft status or existing attempts/sessions/children.
 */
export async function cascadeDeleteRecord(
  db: Env['DB'],
  id: string,
  kind: 'exam' | 'test',
  actor?: AuthUser
): Promise<CascadeDeleteResult> {
  const existingTables = await getExistingTableNames(db);

  if (kind === 'test') {
    const testRow = await db
      .prepare('SELECT id, exam_id, avatar_url FROM generated_tests WHERE id = ?1')
      .bind(id)
      .first<{ id: string; exam_id: string; avatar_url?: string }>();
    if (!testRow) {
      return { deleted: false, avatarKeys: [], syllabusPaths: [] };
    }

    const avatarKey = extractStorageAvatarKey(String(testRow.avatar_url || ''), 'test-avatars/');
    const avatarKeys = avatarKey ? [avatarKey] : [];
    const examKeyPredicate = "exam_id = ?1 OR substr(exam_id, -length(?1) - 2) = '__' || ?1";
    const sessionKeyPredicate = "exam_key = ?1 OR substr(exam_key, -length(?1) - 2) = '__' || ?1";

    const statements: D1PreparedStatement[] = [];
    if (actor) {
      const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
        actionType: 'GENERATED_TEST_DELETED',
        description: `Cascade-deleted generated test '${id}'`,
        targetType: 'generated_test',
        targetId: id,
        condition: { sql: 'EXISTS (SELECT 1 FROM generated_tests WHERE id = ?)', bindings: [id] },
      });
      statements.push(auditStmt);
    }

    statements.push(
      db.prepare(`DELETE FROM attempt_answers WHERE attempt_id IN (SELECT id FROM attempts WHERE ${examKeyPredicate})`).bind(id),
      db.prepare(`DELETE FROM attempts WHERE ${examKeyPredicate}`).bind(id),
    );
    if (existingTables.has('attempt_sessions')) {
      statements.push(db.prepare(`DELETE FROM attempt_sessions WHERE ${sessionKeyPredicate}`).bind(id));
    }
    if (existingTables.has('attempt_locks')) {
      statements.push(db.prepare(`DELETE FROM attempt_locks WHERE ${examKeyPredicate}`).bind(id));
    }
    if (existingTables.has('leaderboard')) {
      statements.push(db.prepare(`DELETE FROM leaderboard WHERE ${examKeyPredicate}`).bind(id));
    }
    if (existingTables.has('admin_analytics_exams')) {
      statements.push(db.prepare(`DELETE FROM admin_analytics_exams WHERE ${examKeyPredicate}`).bind(id));
    }
    if (existingTables.has('admin_analytics_questions')) {
      statements.push(db.prepare(`DELETE FROM admin_analytics_questions WHERE ${examKeyPredicate}`).bind(id));
    }
    if (existingTables.has('admin_analytics_exam_users')) {
      statements.push(db.prepare(`DELETE FROM admin_analytics_exam_users WHERE ${examKeyPredicate}`).bind(id));
    }
    if (existingTables.has('generation_jobs')) {
      statements.push(db.prepare('DELETE FROM generation_jobs WHERE test_id = ?1').bind(id));
    }
    statements.push(db.prepare('DELETE FROM generated_tests WHERE id = ?1').bind(id));

    const result = await db.batch(statements);
    return {
      deleted: result[result.length - 1]?.meta?.changes === 1,
      avatarKeys,
      syllabusPaths: [],
    };
  }

  // Main Exam or Sub-Exam cascade delete (including all descendant sub-exams)
  const examRows = await collectExamTreeRows(db, id);
  if (examRows.length === 0) {
    return { deleted: false, avatarKeys: [], syllabusPaths: [] };
  }

  const examIds = examRows.map((r) => String(r.id)).filter(Boolean);
  const idsJson = JSON.stringify(examIds);

  const avatarKeySet = new Set<string>();
  const syllabusPathSet = new Set<string>();
  for (const row of examRows) {
    const k = extractStorageAvatarKey(String(row.avatar_url || row.image_url || ''), 'exam-avatars/');
    if (k) avatarKeySet.add(k);
    const syl = extractExamSyllabusRef(String(row.syllabus_url || ''));
    if (syl) syllabusPathSet.add(syl);
  }

  try {
    const { results: childTests } = await db
      .prepare('SELECT avatar_url FROM generated_tests WHERE exam_id IN (SELECT value FROM json_each(?1))')
      .bind(idsJson)
      .all<{ avatar_url?: string }>();
    for (const t of childTests || []) {
      const tk = extractStorageAvatarKey(String(t?.avatar_url || ''), 'test-avatars/');
      if (tk) avatarKeySet.add(tk);
    }
  } catch {
    // Ignore optional pre-column read errors; rows will still be deleted in batch.
  }

  const attemptMatch = "EXISTS (SELECT 1 FROM json_each(?1) t WHERE exam_id = t.value OR substr(exam_id, 1, length(t.value) + 2) = t.value || '__')";
  const sessionMatch = "EXISTS (SELECT 1 FROM json_each(?1) t WHERE exam_key = t.value OR substr(exam_key, 1, length(t.value) + 2) = t.value || '__')";
  const examIn = 'exam_id IN (SELECT value FROM json_each(?1))';

  const statements: D1PreparedStatement[] = [];
  if (actor) {
    const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
      actionType: 'EXAM_DELETED',
      description: `Cascade-deleted exam '${id}'`,
      targetType: 'exam',
      targetId: id,
      condition: { sql: 'EXISTS (SELECT 1 FROM exams WHERE id = ?)', bindings: [id] },
    });
    statements.push(auditStmt);
  }

  statements.push(
    db.prepare(`DELETE FROM attempt_answers WHERE attempt_id IN (SELECT id FROM attempts WHERE ${attemptMatch})`).bind(idsJson),
    db.prepare(`DELETE FROM attempts WHERE ${attemptMatch}`).bind(idsJson),
  );
  if (existingTables.has('attempt_sessions')) {
    statements.push(db.prepare(`DELETE FROM attempt_sessions WHERE ${sessionMatch}`).bind(idsJson));
  }
  if (existingTables.has('attempt_locks')) {
    statements.push(db.prepare(`DELETE FROM attempt_locks WHERE ${attemptMatch}`).bind(idsJson));
  }
  if (existingTables.has('question_reports')) {
    statements.push(
      db.prepare(`DELETE FROM question_reports WHERE ${examIn} OR question_id IN (SELECT id FROM questions WHERE ${examIn})`).bind(idsJson),
    );
  }
  if (existingTables.has('bookmarks')) {
    statements.push(db.prepare(`DELETE FROM bookmarks WHERE ${examIn}`).bind(idsJson));
  }
  if (existingTables.has('pinned_exams')) {
    statements.push(db.prepare(`DELETE FROM pinned_exams WHERE ${examIn}`).bind(idsJson));
  }
  if (existingTables.has('leaderboard')) {
    statements.push(db.prepare(`DELETE FROM leaderboard WHERE ${attemptMatch}`).bind(idsJson));
  }
  if (existingTables.has('admin_analytics_exams')) {
    statements.push(db.prepare(`DELETE FROM admin_analytics_exams WHERE ${attemptMatch}`).bind(idsJson));
  }
  if (existingTables.has('admin_analytics_questions')) {
    statements.push(db.prepare(`DELETE FROM admin_analytics_questions WHERE ${attemptMatch}`).bind(idsJson));
  }
  if (existingTables.has('admin_analytics_exam_users')) {
    statements.push(db.prepare(`DELETE FROM admin_analytics_exam_users WHERE ${attemptMatch}`).bind(idsJson));
  }
  if (existingTables.has('generation_jobs')) {
    statements.push(
      db.prepare(`DELETE FROM generation_jobs WHERE ${examIn} OR test_id IN (SELECT id FROM generated_tests WHERE ${examIn})`).bind(idsJson),
    );
  }
  if (existingTables.has('generation_logs')) {
    statements.push(db.prepare(`DELETE FROM generation_logs WHERE ${examIn}`).bind(idsJson));
  }
  statements.push(
    db.prepare(`DELETE FROM questions WHERE ${examIn}`).bind(idsJson),
    db.prepare(`DELETE FROM generated_tests WHERE ${examIn}`).bind(idsJson),
    db.prepare('DELETE FROM exams WHERE id IN (SELECT value FROM json_each(?1))').bind(idsJson),
  );

  const result = await db.batch(statements);
  return {
    deleted: Number(result[result.length - 1]?.meta?.changes || 0) >= 1,
    avatarKeys: [...avatarKeySet],
    syllabusPaths: [...syllabusPathSet],
  };
}

/** Backward-compatible alias executing atomic cascade deletion. */
export async function deleteUnused(db: Env['DB'], id: string, kind: 'exam' | 'test', actor?: AuthUser): Promise<boolean> {
  const res = await cascadeDeleteRecord(db, id, kind, actor);
  return res.deleted;
}
