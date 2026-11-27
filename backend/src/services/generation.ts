import {
  filterDuplicateQuestions,
  GeminiProviderError,
  GeneratedQuestionItem,
  getIstTimeAndDate,
  hashQuestionText,
  incrementTestNumber,
  normalizeQuestionText,
} from "../ai/generator";
import { generateQuestionsWithRouter } from "./aiProviderRouter";
import { resolvePublishStatus } from "../util/publishMode";
import { AuthUser, Env, ExamRow, QuestionRow } from "../types";
import { prepareAdminAuditLog } from "../util/auditLog";

export class GenerationBusyError extends Error {}

export class InsufficientBankQuestionsError extends Error {
  availableCount: number;
  requestedCount: number;
  constructor(message: string, availableCount: number, requestedCount: number) {
    super(message);
    this.availableCount = availableCount;
    this.requestedCount = requestedCount;
  }
}

export interface RunGenerationOptions {
  forceStatus?: "draft" | "paused" | "live";
  aiProvider?: string;
  aiModel?: string;
}

export interface AssembleFromBankOptions {
  testNumber?: string;
  title?: string;
  forceStatus?: "draft" | "paused" | "live";
  auditActor?: AuthUser;
  source?: string;
}

async function loadExistingExamQuestionFingerprints(
  db: D1Database,
  examId: string,
  includeRecentGeneratedTests: boolean = true
): Promise<{ normalized: Set<string>; hashes: Set<string> }> {
  const normalized = new Set<string>();
  const hashes = new Set<string>();

  const [bankRes, recentTestsRes] = await Promise.all([
    db
      .prepare("SELECT question_text FROM questions WHERE exam_id = ? LIMIT 500")
      .bind(examId)
      .all<{ question_text: string }>(),
    includeRecentGeneratedTests
      ? db
          .prepare("SELECT questions_json FROM generated_tests WHERE exam_id = ? ORDER BY generated_at DESC LIMIT 10")
          .bind(examId)
          .all<{ questions_json: string }>()
      : Promise.resolve({ results: [] as Array<{ questions_json: string }> }),
  ]);

  const rawTexts: string[] = [];
  for (const row of bankRes.results || []) {
    if (row.question_text) rawTexts.push(row.question_text);
  }
  for (const testRow of recentTestsRes.results || []) {
    if (!testRow.questions_json) continue;
    try {
      const items = JSON.parse(testRow.questions_json);
      if (Array.isArray(items)) {
        for (const q of items) {
          if (q && typeof q.questionText === "string" && q.questionText.trim()) {
            rawTexts.push(q.questionText);
          }
        }
      }
    } catch {}
  }

  for (const text of rawTexts) {
    const norm = normalizeQuestionText(text);
    if (norm && !normalized.has(norm)) {
      normalized.add(norm);
      hashes.add(await hashQuestionText(text));
    }
  }

  return { normalized, hashes };
}

/** Manual and scheduled generation share one database lease and an atomic publish. */
export async function runGeneration(
  env: Env,
  exam: ExamRow,
  count: number,
  testNumber: string,
  prompt: string,
  source: string,
  requestKey?: string,
  auditActor?: AuthUser,
  options: RunGenerationOptions = {}
) {
  const db = env.DB;
  if (!Number.isInteger(count) || count < 1 || count > 200) throw new Error("Invalid question count");
  const now = Date.now();
  // Expire stale jobs before a repeated request key can short-circuit recovery.
  await db
    .prepare(
      "UPDATE generation_jobs SET status = 'failed', completed_at = ?, error_category = 'TIMEOUT', failed_count = requested_count, retryable = 1 WHERE status = 'running' AND started_at < ?"
    )
    .bind(now, now - 180_000)
    .run();
  if (requestKey) {
    const previous = await db.prepare("SELECT * FROM generation_jobs WHERE request_key = ?").bind(requestKey).first<any>();
    if (previous?.exam_id !== undefined && previous.exam_id !== exam.id) {
      throw new GenerationBusyError("Request key belongs to a different exam");
    }
    if (previous?.status === "success") {
      const persisted = await db
        .prepare("SELECT id, question_count FROM generated_tests WHERE id = ? AND exam_id = ?")
        .bind(previous.test_id, exam.id)
        .first<{ id: string; question_count: number }>();
      if (
        !persisted ||
        !Number.isInteger(previous.generated_count) ||
        previous.generated_count < 1 ||
        persisted.question_count !== previous.generated_count
      ) {
        throw new GenerationBusyError("Completed generation is not confirmed. Refresh the system monitor.");
      }
      return { testId: persisted.id, questionCount: persisted.question_count, jobId: previous.id };
    }
    if (previous) throw new GenerationBusyError("This generation request was already processed. Refresh the monitor.");
  }

  const id = crypto.randomUUID();
  const lease = await db
    .prepare(
      "UPDATE exams SET generating_lock_until = ?, last_generation_status = 'running', last_generation_time = ? WHERE id = ? AND (generating_lock_until IS NULL OR generating_lock_until <= ?)"
    )
    .bind(now + 180_000, now, exam.id, now)
    .run();
  if (!lease.meta.changes) throw new GenerationBusyError("Generation already running for this exam");

  try {
    await db
      .prepare(
        "INSERT INTO generation_jobs (id, exam_id, exam_name, source, requested_count, status, started_at, request_key) VALUES (?, ?, ?, ?, ?, 'running', ?, ?)"
      )
      .bind(id, exam.id, exam.exam_name, source, count, now, requestKey || null)
      .run();

    const effectivePrompt = (prompt || exam.ai_prompt || exam.generation_prompt || exam.custom_prompt_notes || "").trim();
    const { normalized: existingNormalized, hashes: existingHashes } = await loadExistingExamQuestionFingerprints(
      db,
      exam.id,
      source !== "retry"
    );

    const aiResult = await generateQuestionsWithRouter(
      env,
      exam.exam_name,
      exam.syllabus || "",
      count,
      effectivePrompt,
      fetch,
      {
        language: exam.ai_language || "english",
        difficulty: exam.ai_difficulty || "mixed",
        aiProvider: options.aiProvider,
        aiModel: options.aiModel,
        excludedNormalizedTexts: existingNormalized,
      }
    );

    const { unique: dedupedQuestions } = await filterDuplicateQuestions(
      aiResult.questions,
      existingNormalized,
      existingHashes
    );
    if (dedupedQuestions.length !== count) {
      throw new Error("Generated question count was incomplete after deduplication");
    }

    const testId = crypto.randomUUID();
    const finish = Date.now();
    const nextTestNumber = incrementTestNumber(testNumber);
    const status = options.forceStatus || (await resolvePublishStatus(db, exam));

    const statements = [
      db
        .prepare(
          `INSERT INTO generated_tests
        (id, exam_id, exam_name, test_number, title, generated_at, status, question_count, syllabus_used, prompt_used, questions_json)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
        WHERE EXISTS (SELECT 1 FROM exams WHERE id = ? AND generating_lock_until = ?)
          AND EXISTS (SELECT 1 FROM generation_jobs WHERE id = ? AND exam_id = ? AND status = 'running')`
        )
        .bind(
          testId,
          exam.id,
          exam.exam_name,
          testNumber,
          `${exam.exam_name} - ${testNumber}`,
          finish,
          status,
          count,
          exam.syllabus || "",
          prompt,
          JSON.stringify(dedupedQuestions),
          exam.id,
          now + 180_000,
          id,
          exam.id
        ),
      db
        .prepare(
          `UPDATE exams
        SET generating_lock_until = 0, last_generated_date = ?, last_generation_status = 'success',
            last_generation_error = '', last_generation_time = ?, test_number = ?
        WHERE id = ? AND generating_lock_until = ?
          AND EXISTS (SELECT 1 FROM generated_tests WHERE id = ? AND exam_id = ?)`
        )
        .bind(getIstTimeAndDate().todayDate, finish, nextTestNumber, exam.id, now + 180_000, testId, exam.id),
      db
        .prepare(
          `UPDATE generation_jobs
        SET status = 'success', generated_count = ?, completed_at = ?, test_id = ?
        WHERE id = ? AND exam_id = ? AND status = 'running'
          AND EXISTS (SELECT 1 FROM generated_tests WHERE id = ? AND exam_id = ?)`
        )
        .bind(count, finish, testId, id, exam.id, testId, exam.id),
      db
        .prepare(
          `INSERT INTO generation_logs (id, exam_id, exam_name, status, message, timestamp)
        SELECT ?, ?, ?, 'success', ?, ?
        WHERE EXISTS (SELECT 1 FROM generation_jobs WHERE id = ? AND status = 'success' AND test_id = ?)`
        )
        .bind(
          id,
          exam.id,
          exam.exam_name,
          `Generated ${count} questions via ${aiResult.provider}:${aiResult.model}`,
          finish,
          id,
          testId
        ),
    ];

    if (auditActor) {
      const audit = prepareAdminAuditLog(db, auditActor, {
        actionType: "GENERATE_NOW_TRIGGERED",
        description: `Triggered AI generation for exam '${exam.exam_name}' (${exam.id}) via ${aiResult.provider}:${aiResult.model}`,
        targetType: "generated_test",
        targetId: exam.id,
        metadata: {
          examId: exam.id,
          examName: exam.exam_name,
          questionCount: count,
          testNumber,
          provider: aiResult.provider,
          model: aiResult.model,
        },
        condition: {
          sql: `EXISTS (SELECT 1 FROM generated_tests WHERE id = ? AND exam_id = ?)
                AND EXISTS (SELECT 1 FROM generation_jobs WHERE id = ? AND status = 'success' AND test_id = ?)`,
          bindings: [testId, exam.id, id, testId],
        },
      });
      statements.push(audit.stmt);
    }

    if (typeof db.batch !== "function") throw new Error("Atomic D1 batch is required for generation");
    const result = await db.batch(statements);
    if (
      result.length !== statements.length ||
      result.some((row) => row?.success !== true || row?.meta?.changes !== 1)
    ) {
      throw new Error("Generated test publication was not fully acknowledged");
    }

    return {
      testId,
      questionCount: dedupedQuestions.length,
      testNumber,
      nextTestNumber,
      jobId: id,
      status,
      provider: aiResult.provider,
      model: aiResult.model,
    };
  } catch (err) {
    const category = err instanceof GeminiProviderError ? err.code : "GENERATION_FAILED";
    const retryable = err instanceof GeminiProviderError ? err.isTransient : true;
    // Store only categories, never provider output, prompts or database messages.
    try {
      await db.batch([
        db
          .prepare(
            "UPDATE exams SET generating_lock_until = 0, last_generation_status = 'failed', last_generation_error = ?, last_generation_time = ? WHERE id = ? AND generating_lock_until = ?"
          )
          .bind(category, Date.now(), exam.id, now + 180_000),
        db
          .prepare(
            "UPDATE generation_jobs SET status = 'failed', failed_count = requested_count, completed_at = ?, error_category = ?, retryable = ? WHERE id = ?"
          )
          .bind(Date.now(), category, retryable ? 1 : 0, id),
      ]);
    } catch {
      /* The lease expires even if the database is unavailable. */
    }
    throw err;
  }
}

function normalizeBankCorrectAnswer(raw: string): string {
  const s = String(raw || "").trim().toUpperCase();
  if (s === "A" || s === "0") return "A";
  if (s === "B" || s === "1") return "B";
  if (s === "C" || s === "2") return "C";
  if (s === "D" || s === "3") return "D";
  return "";
}

function shuffleArrayInPlace<T>(arr: T[]): T[] {
  for (let i = arr.length - 1; i > 0; i--) {
    const rand = new Uint32Array(1);
    crypto.getRandomValues(rand);
    const j = rand[0] % (i + 1);
    const tmp = arr[i];
    arr[i] = arr[j];
    arr[j] = tmp;
  }
  return arr;
}

/**
 * Zero-cost Question Bank Auto-Assembler (<50ms, Rs 0 AI spend).
 * Builds a fresh mock test from verified bank `questions` preferring questions
 * not used in recent `generated_tests`.
 */
export async function assembleTestFromBank(
  db: D1Database,
  exam: ExamRow,
  count: number,
  options: AssembleFromBankOptions = {}
) {
  const startMs = Date.now();
  if (!Number.isInteger(count) || count < 1 || count > 200) {
    throw new InsufficientBankQuestionsError("questionCount must be an integer between 1 and 200", 0, count);
  }

  const [bankRes, recentTestsRes] = await Promise.all([
    db.prepare("SELECT * FROM questions WHERE exam_id = ?").bind(exam.id).all<QuestionRow>(),
    db
      .prepare("SELECT questions_json FROM generated_tests WHERE exam_id = ? ORDER BY generated_at DESC LIMIT 10")
      .bind(exam.id)
      .all<{ questions_json: string }>(),
  ]);

  const recentNorms = new Set<string>();
  for (const row of recentTestsRes.results || []) {
    if (!row.questions_json) continue;
    try {
      const items = JSON.parse(row.questions_json);
      if (Array.isArray(items)) {
        for (const item of items) {
          if (item && typeof item.questionText === "string") {
            const norm = normalizeQuestionText(item.questionText);
            if (norm) recentNorms.add(norm);
          }
        }
      }
    } catch {}
  }

  const uniqueBankItems: Array<{ norm: string; item: GeneratedQuestionItem }> = [];
  const seenBankNorms = new Set<string>();

  for (const row of bankRes.results || []) {
    const qt = String(row.question_text || "").trim();
    const a = String(row.option_a || "").trim();
    const b = String(row.option_b || "").trim();
    const cOpt = String(row.option_c || "").trim();
    const d = String(row.option_d || "").trim();
    const ans = normalizeBankCorrectAnswer(row.correct_answer);
    if (!qt || !a || !b || !cOpt || !d || !ans) continue;
    const distinctOpts = new Set([a.toLowerCase(), b.toLowerCase(), cOpt.toLowerCase(), d.toLowerCase()]);
    if (distinctOpts.size < 4) continue;
    const norm = normalizeQuestionText(qt);
    if (!norm || seenBankNorms.has(norm)) continue;
    seenBankNorms.add(norm);

    const item: GeneratedQuestionItem = {
      questionText: qt,
      optionA: a,
      optionB: b,
      optionC: cOpt,
      optionD: d,
      correctAnswer: ans,
      explanation: String(row.explanation || "").trim() || `Correct option is ${ans}.`,
    };
    if (row.question_text_hi) item.questionTextHi = row.question_text_hi;
    if (row.option_a_hi) item.optionAHi = row.option_a_hi;
    if (row.option_b_hi) item.optionBHi = row.option_b_hi;
    if (row.option_c_hi) item.optionCHi = row.option_c_hi;
    if (row.option_d_hi) item.optionDHi = row.option_d_hi;
    if (row.explanation_hi) item.explanationHi = row.explanation_hi;

    uniqueBankItems.push({ norm, item });
  }

  if (uniqueBankItems.length < count) {
    throw new InsufficientBankQuestionsError(
      `Question bank for '${exam.exam_name}' only has ${uniqueBankItems.length} valid unique questions, need ${count}.`,
      uniqueBankItems.length,
      count
    );
  }

  const freshPool = shuffleArrayInPlace(uniqueBankItems.filter((x) => !recentNorms.has(x.norm)));
  const recycledPool = shuffleArrayInPlace(uniqueBankItems.filter((x) => recentNorms.has(x.norm)));
  const selected = [...freshPool, ...recycledPool].slice(0, count).map((x) => x.item);

  const testNumber = String(options.testNumber || exam.test_number || "Test 1").trim();
  const nextTestNumber = incrementTestNumber(testNumber);
  const title = String(options.title || `${exam.exam_name} - ${testNumber}`).trim();
  const status = options.forceStatus || (await resolvePublishStatus(db, exam));
  const testId = crypto.randomUUID();
  const logId = crypto.randomUUID();
  const finish = Date.now();
  const freshUsedCount = Math.min(count, freshPool.length);

  const statements = [
    db
      .prepare(
        `INSERT INTO generated_tests
        (id, exam_id, exam_name, test_number, title, generated_at, status, question_count, syllabus_used, prompt_used, questions_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        testId,
        exam.id,
        exam.exam_name,
        testNumber,
        title,
        finish,
        status,
        selected.length,
        "",
        "assembled_from_question_bank",
        JSON.stringify(selected)
      ),
    db
      .prepare(
        "UPDATE exams SET test_number = ?, last_generated_date = ?, last_generation_time = ?, last_generation_status = 'success' WHERE id = ?"
      )
      .bind(nextTestNumber, getIstTimeAndDate().todayDate, finish, exam.id),
    db
      .prepare(
        "INSERT INTO generation_logs (id, exam_id, exam_name, status, message, timestamp) VALUES (?, ?, ?, 'success', ?, ?)"
      )
      .bind(
        logId,
        exam.id,
        exam.exam_name,
        `Assembled ${selected.length} questions from Question Bank (${freshUsedCount} fresh, source=${options.source || "bank"})`,
        finish
      ),
  ];

  if (options.auditActor) {
    const audit = prepareAdminAuditLog(db, options.auditActor, {
      actionType: "ASSEMBLE_FROM_BANK",
      description: `Assembled test '${title}' (${selected.length} Qs) from question bank for '${exam.exam_name}'`,
      targetType: "generated_test",
      targetId: testId,
      metadata: {
        examId: exam.id,
        examName: exam.exam_name,
        questionCount: selected.length,
        freshUsedCount,
        testNumber,
        status,
      },
    });
    statements.push(audit.stmt);
  }

  const results = await db.batch(statements);
  if (results.length !== statements.length || results.some((r) => r?.success !== true)) {
    throw new Error("Bank test assembly could not be committed");
  }

  return {
    testId,
    questionCount: selected.length,
    freshUsedCount,
    testNumber,
    nextTestNumber,
    status,
    title,
    durationMs: Date.now() - startMs,
    sourceBankCount: uniqueBankItems.length,
  };
}
