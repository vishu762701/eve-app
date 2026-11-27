// ============================================================================
// Real-Time Telegram Owner Push Alerts & Shared Bot API Helpers
// ============================================================================

import { Context } from "hono";
import { Env } from "../types";

export function escapeTelegramHtml(raw: unknown): string {
  return String(raw ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export function getTelegramOwnerIds(env: Pick<Env, "TELEGRAM_OWNER_ID">): number[] {
  const raw = String(env.TELEGRAM_OWNER_ID || "").trim();
  if (!raw) return [];
  const ids = raw
    .split(/[,\s]+/)
    .map((part) => Number(part.trim()))
    .filter((num) => Number.isSafeInteger(num) && num > 0);
  return Array.from(new Set(ids));
}

export function isTelegramOwner(env: Pick<Env, "TELEGRAM_OWNER_ID">, telegramUserId: unknown): boolean {
  const numeric = Number(telegramUserId);
  if (!Number.isSafeInteger(numeric) || numeric <= 0) return false;
  return getTelegramOwnerIds(env).includes(numeric);
}

export interface TelegramInlineButton {
  text: string;
  callback_data?: string;
  web_app?: { url: string };
}

export async function callTelegramBotApi(
  env: Pick<Env, "TELEGRAM_BOT_TOKEN">,
  method: string,
  payload: Record<string, any>,
  fetchFn: typeof fetch = fetch
): Promise<any | null> {
  const token = String(env.TELEGRAM_BOT_TOKEN || "").trim();
  if (!token) return null;
  try {
    const res = await fetchFn(`https://api.telegram.org/bot${token}/${method}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(8_000),
    });
    if (!res.ok) return null;
    return await res.json().catch(() => null);
  } catch {
    return null;
  }
}

function dispatchBackground(
  ctx: Context<any, any, any> | undefined,
  task: Promise<unknown>
): void {
  const safe = task.catch(() => {});
  if (ctx) {
    try {
      const execCtx = ctx.executionCtx;
      if (execCtx && typeof execCtx.waitUntil === "function") {
        execCtx.waitUntil(safe);
        return;
      }
    } catch {}
  }
}

export async function notifyOwnersTelegram(
  env: Pick<Env, "TELEGRAM_BOT_TOKEN" | "TELEGRAM_OWNER_ID">,
  text: string,
  inlineKeyboard?: TelegramInlineButton[][],
  fetchFn: typeof fetch = fetch
): Promise<void> {
  const token = String(env.TELEGRAM_BOT_TOKEN || "").trim();
  const ownerIds = getTelegramOwnerIds(env);
  if (!token || ownerIds.length === 0) return;

  await Promise.allSettled(
    ownerIds.map((chatId) =>
      callTelegramBotApi(
        env,
        "sendMessage",
        {
          chat_id: chatId,
          text,
          parse_mode: "HTML",
          disable_web_page_preview: true,
          ...(inlineKeyboard && inlineKeyboard.length > 0
            ? { reply_markup: { inline_keyboard: inlineKeyboard } }
            : {}),
        },
        fetchFn
      )
    )
  );
}

export function alertNewUpiOrder(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  order: {
    orderId: string;
    userEmail: string;
    userId: string;
    amount: number;
    planName: string;
    paymentMethod: string;
    utr?: string;
  }
): void {
  const utrLine = order.utr ? `\n🧾 <b>UTR:</b> <code>${escapeTelegramHtml(order.utr)}</code>` : "";
  const msg =
    `💳 <b>New Premium UPI Order</b>\n` +
    `🆔 <b>Order:</b> <code>${escapeTelegramHtml(order.orderId)}</code>\n` +
    `👤 <b>Student:</b> ${escapeTelegramHtml(order.userEmail || order.userId)}\n` +
    `📦 <b>Plan:</b> ${escapeTelegramHtml(order.planName)} (₹${escapeTelegramHtml(order.amount)})\n` +
    `🔌 <b>Method:</b> ${escapeTelegramHtml(order.paymentMethod.toUpperCase())}` +
    utrLine;

  dispatchBackground(
    c,
    notifyOwnersTelegram(c.env, msg, [
      [
        { text: "✅ Approve", callback_data: `cb:approve_order:${order.orderId}` },
        { text: "❌ Reject", callback_data: `cb:reject_order:${order.orderId}` },
      ],
    ])
  );
}

export function alertFlaggedQuestion(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  flag: {
    reportId: string;
    examName: string;
    reason: string;
    studentEmail: string;
    questionText: string;
    comment?: string;
  }
): void {
  const snippet = String(flag.questionText || "").slice(0, 140);
  const msg =
    `🚩 <b>Question Flagged</b>\n` +
    `📚 <b>Exam:</b> ${escapeTelegramHtml(flag.examName || "Unknown")}\n` +
    `⚠️ <b>Reason:</b> ${escapeTelegramHtml(flag.reason)}\n` +
    `👤 <b>By:</b> ${escapeTelegramHtml(flag.studentEmail || "student")}\n` +
    (flag.comment ? `💬 <b>Note:</b> ${escapeTelegramHtml(flag.comment.slice(0, 140))}\n` : "") +
    `❓ <i>${escapeTelegramHtml(snippet)}</i>`;

  dispatchBackground(
    c,
    notifyOwnersTelegram(c.env, msg, [
      [{ text: "✅ Dismiss Flag", callback_data: `cb:dismiss_flag:${flag.reportId}` }],
    ])
  );
}

export function alertNewFeedback(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  fb: {
    feedbackId: string;
    userName: string;
    userEmail: string;
    message: string;
  }
): void {
  const msg =
    `💬 <b>New Student Feedback</b>\n` +
    `👤 <b>Student:</b> ${escapeTelegramHtml(fb.userName || fb.userEmail || "Student")} (${escapeTelegramHtml(fb.userEmail || "")})\n` +
    `📝 ${escapeTelegramHtml(fb.message.slice(0, 350))}`;

  dispatchBackground(c, notifyOwnersTelegram(c.env, msg));
}

export function alertNewCrashSignature(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  crash: {
    signature: string;
    exceptionClass: string;
    topFrame: string;
    appVersionName: string;
    deviceModel: string;
    reopened: boolean;
  }
): void {
  const label = crash.reopened ? "Reopened Crash Signature" : "New Crash Signature";
  const msg =
    `🚨 <b>${label}</b>\n` +
    `💥 <b>Exception:</b> <code>${escapeTelegramHtml(crash.exceptionClass)}</code>\n` +
    `📍 <b>Frame:</b> <code>${escapeTelegramHtml(crash.topFrame.slice(0, 140))}</code>\n` +
    `📱 <b>App / Device:</b> ${escapeTelegramHtml(crash.appVersionName || "?")} / ${escapeTelegramHtml(crash.deviceModel || "?")}\n` +
    `🔑 <b>Sig:</b> <code>${escapeTelegramHtml(crash.signature)}</code>`;

  dispatchBackground(c, notifyOwnersTelegram(c.env, msg));
}
