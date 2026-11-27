// ============================================================================
// Community Polls & Single-Vote Logic
// ============================================================================

import { Hono } from "hono";
import { requireAdmin, requirePermission } from "../middleware/authMiddleware";
import { AuthUser, Env, PollRow } from "../types";
import { prepareAdminAuditLog } from "../util/auditLog";
import { batchReadRows, getReadDb } from "../util/d1Session";

export const pollRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

async function hasPollQuizColumns(db: D1Database): Promise<boolean> {
  try {
    const { results } = await db.prepare("PRAGMA table_info(polls)").all<{ name?: string }>();
    const names = new Set((results || []).map((r) => String(r?.name || "")));
    return names.has("is_quiz") && names.has("correct_option_index") && names.has("explanation");
  } catch {
    return false;
  }
}

function buildPollResponse(
  p: PollRow,
  options: string[],
  voteCounts: Record<string, number>,
  userVote: number | null,
  isAdmin: boolean
) {
  const isQuiz = p.is_quiz === undefined || p.is_quiz === null ? true : Boolean(p.is_quiz);
  const rawCorrect = Number.isSafeInteger(Number(p.correct_option_index))
    ? Number(p.correct_option_index)
    : -1;
  const hasValidCorrect = isQuiz && rawCorrect >= 0 && rawCorrect < options.length;
  const hasVoted = userVote !== null;
  const canRevealAnswer = hasVoted || isAdmin;
  const correctOptionIndex = hasValidCorrect && canRevealAnswer ? rawCorrect : null;
  const isCorrect = hasValidCorrect && hasVoted ? userVote === rawCorrect : null;
  const rawExplanation = typeof p.explanation === "string" ? p.explanation : "";
  const explanation = canRevealAnswer ? rawExplanation : "";

  return {
    id: p.id,
    question: p.question,
    options,
    createdAt: p.created_at,
    endsAt: p.ends_at,
    active: Boolean(p.active),
    createdBy: p.created_by,
    voteCounts,
    userVote,
    isQuiz,
    correctOptionIndex,
    isCorrect,
    explanation,
  };
}

async function getVoteCountsForPoll(db: D1Database, pollId: string, optionsCount: number): Promise<Record<string, number>> {
  const counts: Record<string, number> = {};
  for (let i = 0; i < optionsCount; i++) {
    counts[String(i)] = 0;
  }

  const { results } = await db
    .prepare("SELECT option_index, COUNT(*) as vote_count FROM poll_votes WHERE poll_id = ? GROUP BY option_index")
    .bind(pollId)
    .all<{ option_index: number; vote_count: number }>();

  for (const r of results || []) {
    // Legacy invalid votes remain on disk for audit, never invent UI options.
    const index = Number(r.option_index);
    if (Number.isSafeInteger(index) && index >= 0 && index < optionsCount) {
      counts[String(index)] = r.vote_count;
    }
  }

  return counts;
}

// GET /api/polls - List all polls (with caller vote included to avoid N+1 fetches)
pollRoutes.get("/", async (c) => {
  const db = getReadDb(c.env.DB);
  const user = c.get("user");
  const pollsStmt = db.prepare("SELECT * FROM polls ORDER BY created_at DESC");
  const countsStmt = db.prepare(
    "SELECT poll_id, option_index, COUNT(*) as vote_count FROM poll_votes GROUP BY poll_id, option_index"
  );
  const stmts = user?.uid
    ? [
        pollsStmt,
        countsStmt,
        db.prepare("SELECT poll_id, option_index FROM poll_votes WHERE user_id = ?").bind(user.uid),
      ]
    : [pollsStmt, countsStmt];

  const batchOut = await batchReadRows<any>(db, stmts);
  const polls = (batchOut[0] || []) as PollRow[];
  const allCounts = (batchOut[1] || []) as Array<{ poll_id: string; option_index: number; vote_count: number }>;
  const myVotes = (batchOut[2] || []) as Array<{ poll_id: string; option_index: number }>;

  const countsByPoll = new Map<string, Record<number, number>>();
  for (const r of allCounts || []) {
    let map = countsByPoll.get(r.poll_id);
    if (!map) {
      map = {};
      countsByPoll.set(r.poll_id, map);
    }
    map[Number(r.option_index)] = Number(r.vote_count || 0);
  }

  const userVoteByPoll = new Map<string, number>();
  for (const v of myVotes || []) {
    userVoteByPoll.set(v.poll_id, Number(v.option_index));
  }

  const isAdmin = user?.isAdmin === true;
  const list: any[] = [];
  for (const p of polls || []) {
    let options: string[] = [];
    try {
      options = JSON.parse(p.options_json);
    } catch (_e) {}

    const rawCounts = countsByPoll.get(p.id) || {};
    const voteCounts: Record<string, number> = {};
    for (let i = 0; i < options.length; i++) {
      voteCounts[String(i)] = rawCounts[i] || 0;
    }
    const userVote = userVoteByPoll.has(p.id) ? (userVoteByPoll.get(p.id) ?? null) : null;
    list.push(buildPollResponse(p, options, voteCounts, userVote, isAdmin));
  }

  return c.json({ success: true, data: list });
});

// GET /api/polls/active - Get current active poll
pollRoutes.get("/active", async (c) => {
  const db = c.env.DB;
  const user = c.get("user");
  const isAdmin = user?.isAdmin === true;
  const now = Date.now();

  const { results: polls } = await db
    .prepare("SELECT * FROM polls WHERE active = 1 ORDER BY created_at DESC LIMIT 5")
    .all<PollRow>();

  for (const p of polls || []) {
    if (p.ends_at > 0 && now > p.ends_at) continue;

    let options: string[] = [];
    try {
      options = JSON.parse(p.options_json);
    } catch (_e) {}

    const voteCounts = await getVoteCountsForPoll(db, p.id, options.length);
    let userVote: number | null = null;
    if (user?.uid) {
      const myVoteRow = await db
        .prepare("SELECT option_index FROM poll_votes WHERE poll_id = ? AND user_id = ?")
        .bind(p.id, user.uid)
        .first<{ option_index: number }>();
      if (myVoteRow && Number.isSafeInteger(Number(myVoteRow.option_index))) {
        userVote = Number(myVoteRow.option_index);
      }
    }
    return c.json({
      success: true,
      data: buildPollResponse(p, options, voteCounts, userVote, isAdmin),
    });
  }

  return c.json({ success: true, data: null });
});

// POST /api/polls - Create poll / quiz (Admin)
pollRoutes.post("/", requirePermission("moderate_feedback"), async (c) => {
  const user = c.get("user");
  const body = await c.req.json().catch(() => ({}));
  const question = String(body.question || "").trim();
  const optionsRaw: string[] = Array.isArray(body.options) ? body.options : [];
  const options = optionsRaw.map((o) => String(o).trim()).filter(Boolean);
  const endsAt = Number(body.endsAt) || 0;
  const active = body.active !== false ? 1 : 0;
  const isQuiz = body.isQuiz !== false;
  const explanation = typeof body.explanation === "string" ? body.explanation.trim().slice(0, 500) : "";

  if (!question || options.length < 2) {
    return c.json({ success: false, error: "Question and at least 2 options are required" }, 400);
  }

  let correctOptionIndex = -1;
  if (
    isQuiz &&
    body.correctOptionIndex !== undefined &&
    body.correctOptionIndex !== null &&
    body.correctOptionIndex !== ""
  ) {
    if (!Number.isSafeInteger(body.correctOptionIndex) ||
        (body.correctOptionIndex !== -1 &&
          (body.correctOptionIndex < 0 || body.correctOptionIndex >= options.length))) {
      return c.json({
        success: false,
        error: "correctOptionIndex must match one of the options",
      }, 400);
    }
    correctOptionIndex = body.correctOptionIndex;
  }

  const id = crypto.randomUUID();
  const now = Date.now();
  const db = c.env.DB;
  const hasQuizCols = await hasPollQuizColumns(db);

  const insertStmt = hasQuizCols
    ? db
        .prepare(
          "INSERT INTO polls (id, question, options_json, created_at, ends_at, active, created_by, is_quiz, correct_option_index, explanation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )
        .bind(
          id,
          question,
          JSON.stringify(options),
          now,
          endsAt,
          active,
          user.email,
          isQuiz ? 1 : 0,
          correctOptionIndex,
          explanation
        )
    : db
        .prepare(
          "INSERT INTO polls (id, question, options_json, created_at, ends_at, active, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)"
        )
        .bind(id, question, JSON.stringify(options), now, endsAt, active, user.email);

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_CREATED",
    description: `Created ${isQuiz ? "quiz" : "poll"} '${question}'`,
    targetType: "poll",
    targetId: id,
    metadata: { question, isQuiz, hasCorrectOption: correctOptionIndex >= 0 },
  });

  await db.batch([insertStmt, auditStmt]);

  return c.json({
    success: true,
    data: { id, isQuiz, correctOptionIndex, explanation },
  }, 201);
});

// PUT /api/polls/:id/status - Toggle poll active status (Admin)
pollRoutes.put("/:id/status", requirePermission("moderate_feedback"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  if (typeof body?.active !== "boolean") {
    return c.json({ success: false, error: "active must be a boolean" }, 400);
  }
  const active = body.active ? 1 : 0;
  const db = c.env.DB;

  const updateStmt = db.prepare("UPDATE polls SET active = ? WHERE id = ?").bind(active, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_STATUS_UPDATED",
    description: `${active ? "Activated" : "Deactivated"} poll '${id}'`,
    targetType: "poll",
    targetId: id,
    metadata: { active: Boolean(active) },
    condition: { sql: "changes() = 1", bindings: [] },
  });
  const results = await db.batch([updateStmt, auditStmt]);
  if (results[0].meta.changes !== 1) {
    return c.json({ success: false, error: "Poll not found" }, 404);
  }
  return c.json({ success: true });
});

// DELETE /api/polls/:id - Delete poll & votes (Admin)
pollRoutes.delete("/:id", requirePermission("moderate_feedback"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "POLL_DELETED",
    description: `Deleted poll '${id}'`,
    targetType: "poll",
    targetId: id,
  });

  await db.batch([
    db.prepare("DELETE FROM poll_votes WHERE poll_id = ?").bind(id),
    db.prepare("DELETE FROM polls WHERE id = ?").bind(id),
    auditStmt,
  ]);

  return c.json({ success: true });
});

// GET /api/polls/:id/vote - Get current user vote
pollRoutes.get("/:id/vote", async (c) => {
  const user = c.get("user");
  const pollId = c.req.param("id");
  const db = c.env.DB;

  const row = await db
    .prepare("SELECT option_index FROM poll_votes WHERE poll_id = ? AND user_id = ?")
    .bind(pollId, user.uid)
    .first<{ option_index: number }>();

  return c.json({ success: true, data: { optionIndex: row ? row.option_index : null } });
});

// POST /api/polls/:id/vote - Submit vote (1 vote per user)
pollRoutes.post("/:id/vote", async (c) => {
  const user = c.get("user");
  const pollId = c.req.param("id");
  const body = await c.req.json().catch(() => ({}));
  const optionIndex = body?.optionIndex;
  const db = c.env.DB;
  const now = Date.now();

  // Retrofit sends an integer. Do not coerce absent, null, boolean, decimal,
  // or string values into another option (Number(null) used to become 0).
  if (!Number.isSafeInteger(optionIndex) || optionIndex < 0) {
    return c.json({ success: false, error: "Valid optionIndex required" }, 400);
  }

  // Check poll exists and is active
  const poll = await db.prepare("SELECT * FROM polls WHERE id = ?").bind(pollId).first<PollRow>();
  if (!poll) {
    return c.json({ success: false, error: "Poll not found" }, 404);
  }
  if (!poll.active || (poll.ends_at > 0 && now > poll.ends_at)) {
    return c.json({ success: false, error: "This poll is closed or has expired." }, 400);
  }

  // A legacy malformed poll must not accept votes that cannot map to a real choice.
  let options: unknown;
  try { options = JSON.parse(poll.options_json); } catch { options = null; }
  if (!Array.isArray(options) || options.length < 2 ||
      !options.every((o: unknown) => typeof o === "string" && o.trim().length > 0)) {
    return c.json({ success: false, error: "Poll options require administrator repair." }, 409);
  }
  if (optionIndex >= options.length) {
    return c.json({ success: false, error: "Option does not belong to this poll." }, 400);
  }

  // PRIMARY KEY (poll_id, user_id) enforces one vote even for simultaneous taps.
  // Confirm the poll is still active at INSERT time as status may change
  // while authorization/validation awaits complete.
  const inserted = await db.prepare(
    "INSERT OR IGNORE INTO poll_votes (poll_id, user_id, option_index, voted_at) " +
    "SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM polls WHERE id = ? AND active = 1 " +
    "AND (ends_at = 0 OR ends_at >= ?))"
  ).bind(pollId, user.uid, optionIndex, now, pollId, now).run();
  if (inserted.meta.changes !== 1) {
    return c.json({ success: false, error: "This poll is closed or you have already voted." }, 409);
  }

  const isQuiz = poll.is_quiz === undefined || poll.is_quiz === null ? true : Boolean(poll.is_quiz);
  const rawCorrect = Number.isSafeInteger(Number(poll.correct_option_index))
    ? Number(poll.correct_option_index)
    : -1;
  const hasValidCorrect = isQuiz && rawCorrect >= 0 && rawCorrect < options.length;
  const correctOptionIndex = hasValidCorrect ? rawCorrect : null;
  const isCorrect = hasValidCorrect ? optionIndex === rawCorrect : null;
  const explanation = typeof poll.explanation === "string" ? poll.explanation : "";

  return c.json({
    success: true,
    data: {
      optionIndex,
      isQuiz,
      correctOptionIndex,
      isCorrect,
      explanation,
    },
  });
});
