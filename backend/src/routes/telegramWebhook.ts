// ============================================================================
// Telegram Bot Admin Control Center Webhook (POST /api/telegram/webhook)
// Strict Secret Token + Owner ID Verification
// ============================================================================

import { Hono } from "hono";
import { parseAndValidateQuestions } from "../ai/generator";
import { deleteUnused } from "../services/deletion";
import {
  assembleTestFromBank,
  GenerationBusyError,
  InsufficientBankQuestionsError,
  runGeneration,
} from "../services/generation";
import {
  callTelegramBotApi,
  escapeTelegramHtml,
  isTelegramOwner,
  TelegramInlineButton,
} from "../services/telegramAdminAlerts";
import { AssetStorage } from "../storage";
import { AuthUser, Env, ExamRow, GeneratedTestRow, PremiumOrderRow } from "../types";
import { normalizeAppConfigObject, readAppConfig, VALID_AI_PROVIDERS } from "../util/appConfig";
import { prepareAdminAuditLog, recordAdminAction, runBatch } from "../util/auditLog";
import { batchReadRows, getReadDb, parseAppConfigRow } from "../util/d1Session";
import { purgeEdgeCache } from "../util/edgeCache";
import { activateUserPremium } from "./premium";

export const telegramWebhookRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

function buildTelegramActor(from: any): AuthUser {
  const id = String(from?.id || "owner");
  const username = String(from?.username || "").trim();
  const displayName = [from?.first_name, from?.last_name].filter(Boolean).join(" ").trim() || "Telegram Owner";
  return {
    uid: `tg:${id}`,
    email: username ? `tg:@${username}` : `tg:${id}@telegram.local`,
    displayName,
    emailVerified: true,
    isAdmin: true,
  };
}

function getMainMenuKeyboard(origin: string): TelegramInlineButton[][] {
  return [
    [
      { text: "📊 Live Status", callback_data: "cb:status" },
      { text: "📝 Exams & Tests", callback_data: "cb:exams" },
    ],
    [
      { text: "💳 Pending UPI Orders", callback_data: "cb:orders" },
      { text: "🚩 Flagged Questions", callback_data: "cb:flags" },
    ],
    [{ text: "📢 Broadcast / Maintenance", callback_data: "cb:system" }],
    [{ text: "⚙️ Open Full Admin Panel", web_app: { url: `${origin}/admin-miniapp` } }],
  ];
}

async function replyMessage(
  env: Env,
  chatId: number | string,
  text: string,
  inlineKeyboard?: TelegramInlineButton[][]
) {
  return callTelegramBotApi(env, "sendMessage", {
    chat_id: chatId,
    text,
    parse_mode: "HTML",
    disable_web_page_preview: true,
    ...(inlineKeyboard && inlineKeyboard.length > 0
      ? { reply_markup: { inline_keyboard: inlineKeyboard } }
      : {}),
  });
}

async function editOrReplyMessage(
  env: Env,
  chatId: number | string,
  messageId: number | undefined,
  text: string,
  inlineKeyboard?: TelegramInlineButton[][]
) {
  if (messageId) {
    const res = await callTelegramBotApi(env, "editMessageText", {
      chat_id: chatId,
      message_id: messageId,
      text,
      parse_mode: "HTML",
      disable_web_page_preview: true,
      ...(inlineKeyboard && inlineKeyboard.length > 0
        ? { reply_markup: { inline_keyboard: inlineKeyboard } }
        : {}),
    });
    if (res) return res;
  }
  return replyMessage(env, chatId, text, inlineKeyboard);
}

async function findExamByQuery(db: D1Database, rawQuery: string): Promise<ExamRow | null> {
  const q = rawQuery.trim();
  if (!q) return null;
  const exact = await db
    .prepare("SELECT * FROM exams WHERE id = ? OR LOWER(exam_name) = LOWER(?) LIMIT 1")
    .bind(q, q)
    .first<ExamRow>();
  if (exact) return exact;
  return await db
    .prepare("SELECT * FROM exams WHERE LOWER(exam_name) LIKE LOWER(?) ORDER BY created_at DESC LIMIT 1")
    .bind(`%${q}%`)
    .first<ExamRow>();
}

async function findStudentByIdentifier(db: D1Database, identifier: string): Promise<any | null> {
  const clean = identifier.trim();
  if (!clean) return null;
  try {
    return await db
      .prepare(
        "SELECT id, email, eve_id, display_name, COALESCE(disabled, 0) AS disabled FROM users WHERE id = ? OR LOWER(email) = LOWER(?) OR LOWER(COALESCE(eve_id, '')) = LOWER(?) LIMIT 1"
      )
      .bind(clean, clean, clean)
      .first<any>();
  } catch {
    return await db
      .prepare("SELECT id, email, display_name FROM users WHERE id = ? OR LOWER(email) = LOWER(?) LIMIT 1")
      .bind(clean, clean)
      .first<any>();
  }
}

async function buildLiveStatusSnapshot(env: Env, origin: string) {
  const readDb = getReadDb(env.DB);
  const now = Date.now();
  const onlineCutoff = now - 5 * 60 * 1000;

  const [
    examsRows,
    liveTestsRows,
    draftTestsRows,
    usersRows,
    onlineRows,
    premRows,
    pendingUpiRows,
    flagsRows,
    cfgRows,
  ] = await batchReadRows<any>(readDb, [
    readDb.prepare("SELECT COUNT(*) AS c FROM exams"),
    readDb.prepare("SELECT COUNT(*) AS c FROM generated_tests WHERE status IN ('live', 'published')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM generated_tests WHERE status IN ('draft', 'paused')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM users"),
    readDb.prepare("SELECT COUNT(*) AS c FROM users WHERE last_active >= ?").bind(onlineCutoff),
    readDb
      .prepare(
        "SELECT COUNT(*) AS c FROM premium_entitlements WHERE status = 'ACTIVE' AND (is_lifetime = 1 OR expires_at IS NULL OR expires_at > ?)"
      )
      .bind(now),
    readDb.prepare("SELECT COUNT(*) AS c FROM premium_orders WHERE status IN ('CREATED', 'PENDING')"),
    readDb.prepare("SELECT COUNT(*) AS c FROM question_reports WHERE status = 'pending'"),
    readDb.prepare("SELECT body FROM app_content WHERE id = 'app_config' LIMIT 1"),
  ]);

  const countVal = (rows: any[]) => Number(rows?.[0]?.c ?? 0);
  const cfg = parseAppConfigRow(cfgRows?.[0]);

  const text =
    `📊 <b>Eve Mock Test — Live Control Status</b>\n\n` +
    `📚 <b>Exams:</b> ${countVal(examsRows)}\n` +
    `📝 <b>Tests:</b> ${countVal(liveTestsRows)} Live · ${countVal(draftTestsRows)} Draft/Paused\n` +
    `👥 <b>Students:</b> ${countVal(usersRows)} Total · 🟢 ${countVal(onlineRows)} Online Now\n` +
    `💎 <b>Premium Active:</b> ${countVal(premRows)}\n` +
    `💳 <b>Pending UPI Orders:</b> ${countVal(pendingUpiRows)}\n` +
    `🚩 <b>Flagged Questions:</b> ${countVal(flagsRows)}\n` +
    `🤖 <b>AI Engine:</b> <code>${escapeTelegramHtml(cfg.aiProvider)}</code>` +
    (cfg.aiModel ? ` / <code>${escapeTelegramHtml(cfg.aiModel)}</code>` : "") +
    `\n🛠 <b>Maintenance Mode:</b> ${cfg.maintenanceMode ? "🔴 ON" : "🟢 OFF"}`;

  return {
    text,
    cfg,
    keyboard: [
      [
        {
          text: cfg.maintenanceMode ? "🟢 Turn Maintenance OFF" : "🔴 Turn Maintenance ON",
          callback_data: "cb:toggle_maint",
        },
        { text: "🔄 Refresh", callback_data: "cb:status" },
      ],
      [{ text: "⚙️ Open Full Admin Panel", web_app: { url: `${origin}/admin-miniapp` } }],
      [{ text: "⬅️ Main Menu", callback_data: "cb:menu" }],
    ] as TelegramInlineButton[][],
  };
}

async function downloadTelegramFile(env: Env, fileId: string): Promise<{ bytes: Uint8Array; filePath: string } | null> {
  const token = String(env.TELEGRAM_BOT_TOKEN || "").trim();
  if (!token || !fileId) return null;
  const meta = await callTelegramBotApi(env, "getFile", { file_id: fileId });
  const filePath = String(meta?.result?.file_path || "").trim();
  if (!filePath) return null;
  try {
    const res = await fetch(`https://api.telegram.org/file/bot${token}/${filePath}`, {
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) return null;
    const bytes = new Uint8Array(await res.arrayBuffer());
    return bytes.byteLength > 0 ? { bytes, filePath } : null;
  } catch {
    return null;
  }
}

async function patchAppConfigFields(
  db: D1Database,
  actor: AuthUser,
  patchFields: Record<string, any>,
  actionType: string,
  description: string
) {
  const prev = await readAppConfig(db).catch(() => normalizeAppConfigObject({}));
  const merged = { ...prev, ...patchFields };
  const normalized = normalizeAppConfigObject(merged);
  const fullBody = {
    ...merged,
    minimum_supported_version_code: normalized.minimumSupportedVersionCode,
    maintenance_mode: normalized.maintenanceMode,
    maintenance_message: normalized.maintenanceMessage,
    default_publish_mode: normalized.defaultPublishMode,
    ai_provider: normalized.aiProvider,
    aiProvider: normalized.aiProvider,
    ai_model: normalized.aiModel,
    aiModel: normalized.aiModel,
  };
  const saveStmt = db
    .prepare(
      "INSERT INTO app_content (id, title, body, updated_at, updated_by) VALUES ('app_config', 'App Configuration', ?, ?, ?) ON CONFLICT(id) DO UPDATE SET body = json_patch(app_content.body, ?), updated_at = excluded.updated_at, updated_by = excluded.updated_by"
    )
    .bind(JSON.stringify(fullBody), Date.now(), actor.email, JSON.stringify(patchFields));
  const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
    actionType,
    description,
    targetType: "app_content",
    targetId: "app_config",
    metadata: patchFields,
  });
  await runBatch(db, [saveStmt, auditStmt]);
  return normalized;
}

telegramWebhookRoutes.post("/", async (c) => {
  const expectedSecret = String(c.env.TELEGRAM_WEBHOOK_SECRET || "").trim();
  if (expectedSecret) {
    const providedSecret = String(c.req.header("X-Telegram-Bot-Api-Secret-Token") || "").trim();
    if (providedSecret !== expectedSecret) {
      return c.json({ success: false, error: "Invalid webhook secret" }, 401);
    }
  }

  const update = await c.req.json().catch(() => null);
  if (!update || typeof update !== "object") {
    return c.json({ ok: true, ignored: true });
  }

  const origin = new URL(c.req.url).origin;
  const db = c.env.DB;

  // 1. Callback Query Handler
  if (update.callback_query && typeof update.callback_query === "object") {
    const cq = update.callback_query;
    if (!isTelegramOwner(c.env, cq.from?.id)) {
      return c.json({ ok: true, ignored: true });
    }
    const actor = buildTelegramActor(cq.from);
    const data = String(cq.data || "").trim();
    const chatId = cq.message?.chat?.id ?? cq.from.id;
    const messageId = cq.message?.message_id;

    let alertText = "Done";

    if (data === "cb:menu") {
      await editOrReplyMessage(
        c.env,
        chatId,
        messageId,
        "🎛 <b>Eve Mock Test — Telegram Admin Control Center</b>\nSelect an action or open the full Admin Mini App:",
        getMainMenuKeyboard(origin)
      );
      alertText = "Main Menu";
    } else if (data === "cb:status") {
      const snap = await buildLiveStatusSnapshot(c.env, origin);
      await editOrReplyMessage(c.env, chatId, messageId, snap.text, snap.keyboard);
      alertText = "Status refreshed";
    } else if (data === "cb:toggle_maint") {
      const curr = await readAppConfig(db).catch(() => normalizeAppConfigObject({}));
      const nextMode = !curr.maintenanceMode;
      await patchAppConfigFields(
        db,
        actor,
        { maintenance_mode: nextMode },
        "MAINTENANCE_TOGGLED",
        `Telegram Bot toggled maintenance mode to ${nextMode ? "ON" : "OFF"}`
      );
      await purgeEdgeCache(c, ["/api/app-config"]);
      const snap = await buildLiveStatusSnapshot(c.env, origin);
      await editOrReplyMessage(c.env, chatId, messageId, snap.text, snap.keyboard);
      alertText = `Maintenance ${nextMode ? "ON" : "OFF"}`;
    } else if (data === "cb:exams") {
      const readDb = getReadDb(db);
      const { results: exams } = await readDb
        .prepare("SELECT id, exam_name, category, question_count FROM exams ORDER BY created_at DESC LIMIT 8")
        .all<ExamRow>();
      const lines = (exams || []).map(
        (e, i) => `${i + 1}. <b>${escapeTelegramHtml(e.exam_name)}</b> (${e.question_count || 20} Qs)`
      );
      const text =
        `📝 <b>Exams & Quick Factory Commands</b>\n\n` +
        (lines.length ? lines.join("\n") : "No exams created yet.") +
        `\n\nUse:\n• <code>/generate Exam Name</code>\n• <code>/assemble Exam Name</code>`;
      await editOrReplyMessage(c.env, chatId, messageId, text, [
        [{ text: "⚙️ Open Full Admin Panel", web_app: { url: `${origin}/admin-miniapp` } }],
        [{ text: "⬅️ Main Menu", callback_data: "cb:menu" }],
      ]);
      alertText = "Exams loaded";
    } else if (data === "cb:orders") {
      const readDb = getReadDb(db);
      const { results: orders } = await readDb
        .prepare(
          "SELECT id, user_email, amount, plan_name, created_at FROM premium_orders WHERE status IN ('CREATED', 'PENDING') ORDER BY created_at DESC LIMIT 5"
        )
        .all<PremiumOrderRow>();
      if (!orders || orders.length === 0) {
        await editOrReplyMessage(c.env, chatId, messageId, "✅ <b>No pending UPI orders right now.</b>", [
          [{ text: "⬅️ Main Menu", callback_data: "cb:menu" }],
        ]);
      } else {
        const text = orders
          .map(
            (o) =>
              `💳 <code>${escapeTelegramHtml(o.id)}</code> — ₹${o.amount} (${escapeTelegramHtml(o.user_email || "user")})`
          )
          .join("\n");
        const buttons: TelegramInlineButton[][] = orders.map((o) => [
          { text: `✅ Approve ${o.id.slice(-6)}`, callback_data: `cb:approve_order:${o.id}` },
          { text: `❌ Reject ${o.id.slice(-6)}`, callback_data: `cb:reject_order:${o.id}` },
        ]);
        buttons.push([{ text: "⬅️ Main Menu", callback_data: "cb:menu" }]);
        await editOrReplyMessage(c.env, chatId, messageId, `💳 <b>Pending UPI Orders</b>\n\n${text}`, buttons);
      }
      alertText = "Orders loaded";
    } else if (data === "cb:flags") {
      const readDb = getReadDb(db);
      const { results: flags } = await readDb
        .prepare(
          "SELECT id, exam_name, reason, question_text FROM question_reports WHERE status = 'pending' ORDER BY timestamp DESC LIMIT 5"
        )
        .all<any>();
      if (!flags || flags.length === 0) {
        await editOrReplyMessage(c.env, chatId, messageId, "✅ <b>Zero pending flagged questions!</b>", [
          [{ text: "⬅️ Main Menu", callback_data: "cb:menu" }],
        ]);
      } else {
        const text = flags
          .map(
            (f) =>
              `🚩 <b>${escapeTelegramHtml(f.exam_name || "Exam")}</b> — ${escapeTelegramHtml(f.reason)}\n<i>${escapeTelegramHtml(String(f.question_text || "").slice(0, 80))}</i>`
          )
          .join("\n\n");
        const buttons: TelegramInlineButton[][] = flags.map((f) => [
          { text: `✅ Dismiss ${String(f.id).slice(0, 8)}`, callback_data: `cb:dismiss_flag:${f.id}` },
        ]);
        buttons.push([{ text: "⬅️ Main Menu", callback_data: "cb:menu" }]);
        await editOrReplyMessage(c.env, chatId, messageId, `🚩 <b>Flagged Questions</b>\n\n${text}`, buttons);
      }
      alertText = "Flags loaded";
    } else if (data === "cb:system") {
      const cfg = await readAppConfig(getReadDb(db)).catch(() => normalizeAppConfigObject({}));
      const text =
        `📢 <b>System, AI & Broadcast Controls</b>\n\n` +
        `🛠 <b>Maintenance:</b> ${cfg.maintenanceMode ? "🔴 ON" : "🟢 OFF"}\n` +
        `🤖 <b>AI Router:</b> <code>${escapeTelegramHtml(cfg.aiProvider)}</code> ${cfg.aiModel ? `(${escapeTelegramHtml(cfg.aiModel)})` : ""}\n\n` +
        `<b>Quick Commands:</b>\n` +
        `• <code>/maintenance on|off [msg]</code>\n` +
        `• <code>/broadcast Title | Message</code>\n` +
        `• <code>/model groq llama-3.3-70b-versatile</code>\n` +
        `• Send Photo/Video with <code>/banner [url]</code>\n` +
        `• Send PDF with <code>/syllabus Exam Name</code>`;
      await editOrReplyMessage(c.env, chatId, messageId, text, [
        [
          {
            text: cfg.maintenanceMode ? "🟢 Disable Maintenance" : "🔴 Enable Maintenance",
            callback_data: "cb:toggle_maint",
          },
        ],
        [{ text: "⬅️ Main Menu", callback_data: "cb:menu" }],
      ]);
      alertText = "System menu";
    } else if (data.startsWith("cb:publish:")) {
      const testId = data.slice("cb:publish:".length).trim();
      const test = await db
        .prepare("SELECT * FROM generated_tests WHERE id = ?")
        .bind(testId)
        .first<GeneratedTestRow>();
      if (!test) {
        alertText = "Test not found";
      } else {
        let valid = true;
        try {
          const parsed = parseAndValidateQuestions(test.questions_json);
          if (parsed.length !== test.question_count) valid = false;
        } catch {
          valid = false;
        }
        if (!valid) {
          alertText = "Test questions failed validation";
        } else {
          const updateStmt = db.prepare("UPDATE generated_tests SET status = 'live' WHERE id = ?").bind(testId);
          const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
            actionType: "GENERATED_TEST_STATUS_UPDATED",
            description: `Telegram Bot published test '${testId}' live`,
            targetType: "generated_test",
            targetId: testId,
            metadata: { status: "live" },
          });
          await db.batch([updateStmt, auditStmt]);
          await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
          await editOrReplyMessage(
            c.env,
            chatId,
            messageId,
            `🚀 <b>Published Live!</b>\nTest: <b>${escapeTelegramHtml(test.title)}</b> (${test.question_count} Qs)\nID: <code>${escapeTelegramHtml(testId)}</code>`,
            [[{ text: "📊 Live Status", callback_data: "cb:status" }]]
          );
          alertText = "Published Live!";
        }
      }
    } else if (data.startsWith("cb:delete_test:")) {
      const testId = data.slice("cb:delete_test:".length).trim();
      const deleted = await deleteUnused(db, testId, "test", actor).catch(() => false);
      if (deleted) {
        await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
        await editOrReplyMessage(
          c.env,
          chatId,
          messageId,
          `🗑 <b>Deleted test</b> <code>${escapeTelegramHtml(testId)}</code>.`
        );
        alertText = "Test deleted";
      } else {
        alertText = "Cannot delete (already attempted or missing)";
      }
    } else if (data.startsWith("cb:delete_banner:")) {
      const bannerId = data.slice("cb:delete_banner:".length).trim();
      const row = await db
        .prepare("SELECT storage_path FROM home_banners WHERE id = ?")
        .bind(bannerId)
        .first<{ storage_path?: string }>();
      if (!row) {
        alertText = "Banner not found";
      } else {
        const delStmt = db.prepare("DELETE FROM home_banners WHERE id = ?").bind(bannerId);
        const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
          actionType: "BANNER_DELETED",
          description: `Telegram Bot deleted banner '${bannerId}'`,
          targetType: "banner",
          targetId: bannerId,
        });
        await db.batch([delStmt, auditStmt]);
        if (row.storage_path) {
          try {
            await new AssetStorage(c.env).deleteFiles([row.storage_path]);
          } catch {}
        }
        await purgeEdgeCache(c, ["/api/banners"]);
        await editOrReplyMessage(
          c.env,
          chatId,
          messageId,
          `🗑 <b>Banner removed</b> (<code>${escapeTelegramHtml(bannerId)}</code>)`
        );
        alertText = "Banner deleted";
      }
    } else if (data.startsWith("cb:approve_order:")) {
      const orderId = data.slice("cb:approve_order:".length).trim();
      const order = await db
        .prepare("SELECT * FROM premium_orders WHERE id = ?")
        .bind(orderId)
        .first<PremiumOrderRow>();
      if (!order) {
        alertText = "Order not found";
      } else if (order.status === "SUCCESS" || order.status === "PAID") {
        alertText = "Order already approved";
      } else {
        const now = Date.now();
        await db
          .prepare("UPDATE premium_orders SET status = 'SUCCESS', provider_payment_id = ?, paid_at = ? WHERE id = ?")
          .bind(`TG_ADMIN_${now}`, now, orderId)
          .run();
        await activateUserPremium(
          db,
          order.user_id,
          order.plan_id,
          order.plan_name,
          order.id,
          `TG_ADMIN_${now}`,
          order.duration_days,
          Boolean(order.is_lifetime),
          "TELEGRAM_ADMIN_APPROVAL",
          {
            user: actor,
            options: {
              actionType: "PREMIUM_ORDER_APPROVED",
              description: `Approved UPI order '${orderId}' for ${order.user_email || order.user_id} via Telegram Bot`,
              targetType: "premium_order",
              targetId: orderId,
              metadata: { amount: order.amount, planName: order.plan_name },
            },
          }
        );
        await editOrReplyMessage(
          c.env,
          chatId,
          messageId,
          `✅ <b>UPI Order Approved & Premium Activated!</b>\n🆔 <code>${escapeTelegramHtml(orderId)}</code>\n👤 ${escapeTelegramHtml(order.user_email || order.user_id)} · ₹${order.amount}`
        );
        alertText = "Order approved!";
      }
    } else if (data.startsWith("cb:reject_order:")) {
      const orderId = data.slice("cb:reject_order:".length).trim();
      const order = await db
        .prepare("SELECT * FROM premium_orders WHERE id = ?")
        .bind(orderId)
        .first<PremiumOrderRow>();
      if (!order) {
        alertText = "Order not found";
      } else {
        const updateStmt = db
          .prepare("UPDATE premium_orders SET status = 'FAILED' WHERE id = ?")
          .bind(orderId);
        const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
          actionType: "PREMIUM_ORDER_REJECTED",
          description: `Rejected UPI order '${orderId}' via Telegram Bot`,
          targetType: "premium_order",
          targetId: orderId,
        });
        await db.batch([updateStmt, auditStmt]);
        await editOrReplyMessage(
          c.env,
          chatId,
          messageId,
          `❌ <b>UPI Order Rejected</b> (<code>${escapeTelegramHtml(orderId)}</code>)`
        );
        alertText = "Order rejected";
      }
    } else if (data.startsWith("cb:dismiss_flag:")) {
      const reportId = data.slice("cb:dismiss_flag:".length).trim();
      const updateStmt = db
        .prepare("UPDATE question_reports SET status = 'dismissed' WHERE id = ? AND status = 'pending'")
        .bind(reportId);
      const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
        actionType: "QUESTION_REPORT_DISMISSED",
        description: `Dismissed flagged question '${reportId}' via Telegram Bot`,
        targetType: "question_report",
        targetId: reportId,
      });
      await db.batch([updateStmt, auditStmt]);
      await editOrReplyMessage(
        c.env,
        chatId,
        messageId,
        `✅ <b>Flagged Report Dismissed</b> (<code>${escapeTelegramHtml(reportId)}</code>)`
      );
      alertText = "Flag dismissed";
    }

    await callTelegramBotApi(c.env, "answerCallbackQuery", {
      callback_query_id: cq.id,
      text: alertText,
    });
    return c.json({ ok: true });
  }

  // 2. Message / Command / Media Handler
  const msg = update.message;
  if (!msg || !isTelegramOwner(c.env, msg.from?.id)) {
    return c.json({ ok: true, ignored: true });
  }

  const chatId = msg.chat?.id ?? msg.from.id;
  const actor = buildTelegramActor(msg.from);
  const caption = String(msg.caption || "").trim();
  const text = String(msg.text || "").trim();

  // Direct Banner Upload (Photo or Video with caption /banner [optional_url])
  if ((Array.isArray(msg.photo) && msg.photo.length > 0) || msg.video) {
    if (caption.toLowerCase().startsWith("/banner")) {
      const arg = caption.slice("/banner".length).trim();
      let linkUrl = "";
      if (arg && (arg.startsWith("https://") || arg.startsWith("http://"))) {
        try {
          linkUrl = new URL(arg).href;
        } catch {}
      }

      const isVideo = Boolean(msg.video);
      const fileId = isVideo
        ? String(msg.video.file_id || "")
        : String(msg.photo[msg.photo.length - 1]?.file_id || "");

      const downloaded = await downloadTelegramFile(c.env, fileId);
      if (!downloaded) {
        await replyMessage(c.env, chatId, "❌ Could not download media from Telegram. Check bot token & file size.");
        return c.json({ ok: true });
      }

      const ext = isVideo ? "mp4" : "jpg";
      const mime = isVideo ? String(msg.video?.mime_type || "video/mp4") : "image/jpeg";
      const mediaType = isVideo ? "video" : "image";
      const bannerId = crypto.randomUUID();
      const storagePath = `banners/${bannerId}.${ext}`;
      const storage = new AssetStorage(c.env);
      const storedLoc = await storage.uploadFile(storagePath, downloaded.bytes, mime);
      const persistedStoragePath = storedLoc.startsWith("tg:") ? storedLoc : storagePath;
      const imageUrl = `/api/banners/${encodeURIComponent(bannerId)}/media?v=${Date.now()}`;

      const maxOrderRow = await db
        .prepare("SELECT MAX(order_index) as max_order FROM home_banners")
        .first<{ max_order: number | null }>()
        .catch(() => ({ max_order: -1 }));
      const nextOrder = (maxOrderRow?.max_order ?? -1) + 1;
      const now = Date.now();

      const insertStmt = db
        .prepare(
          `INSERT INTO home_banners (
            id, image_url, storage_path, order_index, uploaded_at, uploaded_by, active, link_url, link_label, media_type
          ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, '', ?)`
        )
        .bind(bannerId, imageUrl, persistedStoragePath, nextOrder, now, actor.email, linkUrl, mediaType);
      const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
        actionType: "BANNER_ADDED",
        description: `Uploaded ${mediaType} banner '${bannerId}' from Telegram Bot chat`,
        targetType: "banner",
        targetId: bannerId,
        metadata: { mediaType, linkUrl },
      });
      await db.batch([insertStmt, auditStmt]);
      await purgeEdgeCache(c, ["/api/banners"]);

      await replyMessage(
        c.env,
        chatId,
        `🖼 <b>Home Banner Published!</b>\n🆔 <code>${escapeTelegramHtml(bannerId)}</code>\n📦 Type: ${mediaType.toUpperCase()}${linkUrl ? `\n🔗 Link: ${escapeTelegramHtml(linkUrl)}` : ""}`,
        [[{ text: "🗑 Delete Banner", callback_data: `cb:delete_banner:${bannerId}` }]]
      );
      return c.json({ ok: true });
    }
  }

  // Direct Syllabus PDF Upload (Document with caption /syllabus <exam_name>)
  if (msg.document && caption.toLowerCase().startsWith("/syllabus")) {
    const examQuery = caption.slice("/syllabus".length).trim();
    const exam = await findExamByQuery(db, examQuery);
    if (!exam) {
      await replyMessage(
        c.env,
        chatId,
        `❌ Exam not found for syllabus upload: <b>${escapeTelegramHtml(examQuery || "(empty)")}</b>`
      );
      return c.json({ ok: true });
    }

    const downloaded = await downloadTelegramFile(c.env, String(msg.document.file_id || ""));
    if (!downloaded) {
      await replyMessage(c.env, chatId, "❌ Failed to download PDF file from Telegram.");
      return c.json({ ok: true });
    }
    const header = new TextDecoder().decode(downloaded.bytes.slice(0, 5));
    if (!header.startsWith("%PDF")) {
      await replyMessage(c.env, chatId, "❌ Uploaded document is not a valid PDF (%PDF header missing).");
      return c.json({ ok: true });
    }

    const storage = new AssetStorage(c.env);
    const storagePath = `syllabi/${exam.id}_${Date.now()}.pdf`;
    const storedLoc = await storage.uploadFile(storagePath, downloaded.bytes, "application/pdf");
    const refForUrl = storedLoc.startsWith("tg:") ? storedLoc : storagePath;
    const downloadUrl = `/api/exams/${encodeURIComponent(exam.id)}/syllabus?ref=${encodeURIComponent(refForUrl)}&v=${Date.now()}`;
    const fileName = String(msg.document.file_name || `${exam.exam_name}_syllabus.pdf`).slice(0, 120);

    const updateStmt = db
      .prepare("UPDATE exams SET syllabus_url = ?, syllabus_file_name = ? WHERE id = ?")
      .bind(downloadUrl, fileName, exam.id);
    const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
      actionType: "SYLLABUS_UPLOADED",
      description: `Uploaded PDF syllabus for '${exam.exam_name}' via Telegram Bot chat`,
      targetType: "exam",
      targetId: exam.id,
      metadata: { fileName },
    });
    await db.batch([updateStmt, auditStmt]);
    await purgeEdgeCache(c, ["/api/exams"]);

    await replyMessage(
      c.env,
      chatId,
      `📄 <b>Syllabus PDF Attached!</b>\n📚 Exam: <b>${escapeTelegramHtml(exam.exam_name)}</b>\n📎 File: <code>${escapeTelegramHtml(fileName)}</code>`
    );
    return c.json({ ok: true });
  }

  if (!text.startsWith("/")) {
    return c.json({ ok: true });
  }

  const spaceIdx = text.indexOf(" ");
  const rawCmd = (spaceIdx >= 0 ? text.slice(0, spaceIdx) : text).toLowerCase();
  const cmd = rawCmd.split("@")[0];
  const args = spaceIdx >= 0 ? text.slice(spaceIdx + 1).trim() : "";

  if (cmd === "/start" || cmd === "/admin") {
    await replyMessage(
      c.env,
      chatId,
      "🎛 <b>Eve Mock Test — Telegram Admin Control Center</b>\nSelect an action or open the full Admin Mini App:",
      getMainMenuKeyboard(origin)
    );
    return c.json({ ok: true });
  }

  if (cmd === "/status") {
    const snap = await buildLiveStatusSnapshot(c.env, origin);
    await replyMessage(c.env, chatId, snap.text, snap.keyboard);
    return c.json({ ok: true });
  }

  if (cmd === "/generate") {
    const exam = await findExamByQuery(db, args);
    if (!exam) {
      await replyMessage(
        c.env,
        chatId,
        `❌ Exam not found: <b>${escapeTelegramHtml(args || "(provide exam name)")}</b>`
      );
      return c.json({ ok: true });
    }
    try {
      const count = exam.question_count || 20;
      const testNumber = exam.test_number || "Test 1";
      const prompt = (exam.ai_prompt || exam.generation_prompt || exam.custom_prompt_notes || "").trim();
      const res = await runGeneration(
        c.env,
        exam,
        count,
        testNumber,
        prompt,
        "telegram_bot",
        undefined,
        actor,
        { forceStatus: "draft" }
      );
      await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
      await replyMessage(
        c.env,
        chatId,
        `✨ <b>AI Draft Test Ready!</b>\n` +
          `📚 <b>Exam:</b> ${escapeTelegramHtml(exam.exam_name)}\n` +
          `📝 <b>Test:</b> ${escapeTelegramHtml(testNumber)} (${res.questionCount} Qs)\n` +
          `🤖 <b>Provider:</b> <code>${escapeTelegramHtml(res.provider || "ai")}:${escapeTelegramHtml(res.model || "")}</code>\n` +
          `📌 <b>Status:</b> draft\n🆔 <code>${escapeTelegramHtml(res.testId)}</code>`,
        [
          [
            { text: "🚀 Publish Live", callback_data: `cb:publish:${res.testId}` },
            { text: "🗑 Delete", callback_data: `cb:delete_test:${res.testId}` },
          ],
        ]
      );
    } catch (err: any) {
      if (err instanceof GenerationBusyError) {
        await replyMessage(c.env, chatId, `⏳ Generation already running for <b>${escapeTelegramHtml(exam.exam_name)}</b>.`);
      } else {
        await replyMessage(
          c.env,
          chatId,
          `❌ Generation failed: ${escapeTelegramHtml(err?.userFacingMessage || err?.message || "Unknown error")}`
        );
      }
    }
    return c.json({ ok: true });
  }

  if (cmd === "/assemble") {
    const exam = await findExamByQuery(db, args);
    if (!exam) {
      await replyMessage(
        c.env,
        chatId,
        `❌ Exam not found: <b>${escapeTelegramHtml(args || "(provide exam name)")}</b>`
      );
      return c.json({ ok: true });
    }
    try {
      const count = exam.question_count || 20;
      const res = await assembleTestFromBank(db, exam, count, {
        forceStatus: "draft",
        auditActor: actor,
        source: "telegram_bot",
      });
      await purgeEdgeCache(c, ["/api/generated-tests", "/api/exams"]);
      await replyMessage(
        c.env,
        chatId,
        `🏦 <b>Zero-Cost Bank Test Assembled!</b> (⚡ ${res.durationMs}ms · ₹0)\n` +
          `📚 <b>Exam:</b> ${escapeTelegramHtml(exam.exam_name)}\n` +
          `📝 <b>Title:</b> ${escapeTelegramHtml(res.title)} (${res.questionCount} Qs, ${res.freshUsedCount} fresh)\n` +
          `🆔 <code>${escapeTelegramHtml(res.testId)}</code>`,
        [
          [
            { text: "🚀 Publish Live", callback_data: `cb:publish:${res.testId}` },
            { text: "🗑 Delete", callback_data: `cb:delete_test:${res.testId}` },
          ],
        ]
      );
    } catch (err: any) {
      const msgText =
        err instanceof InsufficientBankQuestionsError
          ? err.message
          : err?.message || "Question bank assembly failed";
      await replyMessage(c.env, chatId, `❌ ${escapeTelegramHtml(msgText)}`);
    }
    return c.json({ ok: true });
  }

  if (cmd === "/maintenance") {
    const parts = args.split(/\s+/);
    const modeWord = (parts[0] || "").toLowerCase();
    if (modeWord !== "on" && modeWord !== "off") {
      await replyMessage(c.env, chatId, "Usage: <code>/maintenance on|off [optional message]</code>");
      return c.json({ ok: true });
    }
    const enable = modeWord === "on";
    const optionalMsg = args.slice(parts[0].length).trim();
    const patch: Record<string, any> = { maintenance_mode: enable };
    if (optionalMsg) patch.maintenance_message = optionalMsg;
    await patchAppConfigFields(
      db,
      actor,
      patch,
      "MAINTENANCE_TOGGLED",
      `Maintenance set to ${enable ? "ON" : "OFF"} via Telegram Bot`
    );
    await purgeEdgeCache(c, ["/api/app-config"]);
    await replyMessage(
      c.env,
      chatId,
      `🛠 Maintenance Mode is now <b>${enable ? "🔴 ON" : "🟢 OFF"}</b>.${optionalMsg ? `\n📝 Message: ${escapeTelegramHtml(optionalMsg)}` : ""}`
    );
    return c.json({ ok: true });
  }

  if (cmd === "/broadcast") {
    const sep = args.indexOf("|");
    if (sep <= 0) {
      await replyMessage(c.env, chatId, "Usage: <code>/broadcast Title | Message body</code>");
      return c.json({ ok: true });
    }
    const title = args.slice(0, sep).trim().slice(0, 120);
    const message = args.slice(sep + 1).trim().slice(0, 4000);
    if (!title || !message) {
      await replyMessage(c.env, chatId, "❌ Both title and message are required.");
      return c.json({ ok: true });
    }
    const notifId = crypto.randomUUID();
    const now = Date.now();
    const insertStmt = db
      .prepare(
        "INSERT INTO notifications (id, title, message, sent_at, sent_by, type, target_category) VALUES (?, ?, ?, ?, ?, 'general', 'All Users')"
      )
      .bind(notifId, title, message, now, actor.email);
    const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
      actionType: "BROADCAST_SENT",
      description: `Sent broadcast '${title}' via Telegram Bot`,
      targetType: "notification",
      targetId: notifId,
    });
    await db.batch([insertStmt, auditStmt]);
    await replyMessage(
      c.env,
      chatId,
      `📢 <b>Broadcast Sent!</b>\n<b>${escapeTelegramHtml(title)}</b>\n${escapeTelegramHtml(message)}`
    );
    return c.json({ ok: true });
  }

  if (cmd === "/model") {
    const parts = args.split(/\s+/).filter(Boolean);
    const provider = (parts[0] || "").toLowerCase();
    const modelId = parts.slice(1).join(" ").trim();
    if (!(VALID_AI_PROVIDERS as readonly string[]).includes(provider)) {
      await replyMessage(
        c.env,
        chatId,
        `Usage: <code>/model &lt;${VALID_AI_PROVIDERS.join("|")}&gt; [model_id]</code>`
      );
      return c.json({ ok: true });
    }
    await patchAppConfigFields(
      db,
      actor,
      {
        ai_provider: provider,
        aiProvider: provider,
        ai_model: modelId,
        aiModel: modelId,
      },
      "CONFIG_UPDATED",
      `Updated AI router to ${provider}${modelId ? ` (${modelId})` : ""} via Telegram Bot`
    );
    await purgeEdgeCache(c, ["/api/app-config"]);
    await replyMessage(
      c.env,
      chatId,
      `🤖 <b>AI Router Updated!</b>\n🔌 Provider: <code>${escapeTelegramHtml(provider)}</code>\n🧠 Model: <code>${escapeTelegramHtml(modelId || "(provider default)")}</code>`
    );
    return c.json({ ok: true });
  }

  if (cmd === "/ban" || cmd === "/unban") {
    if (!args) {
      await replyMessage(c.env, chatId, `Usage: <code>${cmd} &lt;email_or_eve_id&gt;</code>`);
      return c.json({ ok: true });
    }
    const targetUser = await findStudentByIdentifier(db, args);
    if (!targetUser) {
      await replyMessage(c.env, chatId, `❌ Student not found: <code>${escapeTelegramHtml(args)}</code>`);
      return c.json({ ok: true });
    }
    const disabledVal = cmd === "/ban" ? 1 : 0;
    const updStmt = db.prepare("UPDATE users SET disabled = ? WHERE id = ?").bind(disabledVal, targetUser.id);
    const { stmt: auditStmt } = prepareAdminAuditLog(db, actor, {
      actionType: disabledVal ? "USER_BANNED" : "USER_UNBANNED",
      description: `${disabledVal ? "Banned" : "Unbanned"} ${targetUser.email || targetUser.id} via Telegram Bot`,
      targetType: "user",
      targetId: targetUser.id,
    });
    await db.batch([updStmt, auditStmt]);
    await replyMessage(
      c.env,
      chatId,
      `${disabledVal ? "🚫 <b>Student Banned</b>" : "✅ <b>Student Unbanned</b>"}\n👤 ${escapeTelegramHtml(targetUser.email || targetUser.display_name || targetUser.id)}`
    );
    return c.json({ ok: true });
  }

  if (cmd === "/premium") {
    const parts = args.split(/\s+/).filter(Boolean);
    const identifier = parts[0] || "";
    const daysRaw = parts[1] || "30";
    if (!identifier) {
      await replyMessage(c.env, chatId, "Usage: <code>/premium &lt;email_or_eve_id&gt; &lt;days&gt;</code>");
      return c.json({ ok: true });
    }
    const targetUser = await findStudentByIdentifier(db, identifier);
    if (!targetUser) {
      await replyMessage(c.env, chatId, `❌ Student not found: <code>${escapeTelegramHtml(identifier)}</code>`);
      return c.json({ ok: true });
    }
    const isLifetime = daysRaw.toLowerCase() === "lifetime";
    const days = isLifetime ? 36500 : Math.max(1, Math.min(3650, parseInt(daysRaw, 10) || 30));
    await activateUserPremium(
      db,
      targetUser.id,
      "telegram_admin",
      isLifetime ? "Lifetime Grant" : `${days}-Day Premium`,
      `TG_GRANT_${Date.now()}`,
      actor.uid,
      days,
      isLifetime,
      "TELEGRAM_BOT_GRANT",
      {
        user: actor,
        options: {
          actionType: "PREMIUM_GRANTED",
          description: `Granted ${isLifetime ? "Lifetime" : `${days}d`} Premium to ${targetUser.email || targetUser.id} via Telegram Bot`,
          targetType: "user",
          targetId: targetUser.id,
        },
      }
    );
    await replyMessage(
      c.env,
      chatId,
      `💎 <b>Premium Activated!</b>\n👤 ${escapeTelegramHtml(targetUser.email || targetUser.id)}\n⏳ Duration: ${isLifetime ? "Lifetime" : `${days} days`}`
    );
    return c.json({ ok: true });
  }

  await replyMessage(
    c.env,
    chatId,
    "Unknown command. Tap /admin to open the Control Center menu.",
    getMainMenuKeyboard(origin)
  );
  return c.json({ ok: true });
});
