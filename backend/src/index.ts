// ============================================================================
// Eve Mock Test App — Cloudflare Worker Backend API
// ============================================================================

import { monitoringRoutes } from "./routes/monitoring";
import { apiErrorContract, adminJsonContract } from "./middleware/apiContract";
import { HTTPException } from "hono/http-exception";
import { operationMonitor } from "./middleware/operationMonitor";
import { Context, Hono } from "hono";
import { cors } from "hono/cors";
import { handleScheduledTestGeneration } from "./cron/scheduledTestGeneration";
import { readAppConfig } from "./util/appConfig";
import { serveStoredAvatarResponse } from "./util/avatarMedia";
import { getReadDb } from "./util/d1Session";
import { matchStudentEdgeCache, storeStudentEdgeCache } from "./util/edgeCache";
import { authMiddleware } from "./middleware/authMiddleware";
import { ipRateLimit, userRateLimit } from "./middleware/rateLimiter";
import { adminRoutes } from "./routes/admin";
import { appContentRoutes } from "./routes/appContent";
import { attemptRoutes } from "./routes/attempts";
import { authRoutes } from "./routes/auth";
import { bannerRoutes } from "./routes/banners";
import { bookmarkRoutes } from "./routes/bookmarks";
import { broadcastRoutes } from "./routes/broadcasts";
import { examRoutes } from "./routes/exams";
import { feedbackRoutes } from "./routes/feedback";
import { generatedTestRoutes } from "./routes/generatedTests";
import { leaderboardRoutes } from "./routes/leaderboard";
import { pinRoutes } from "./routes/pins";
import { pollRoutes } from "./routes/polls";
import { premiumRoutes } from "./routes/premium";
import { reportRoutes, adminReportRoutes, adminAuditLogRoutes } from "./routes/reports";
import { typographyRoutes } from "./routes/typographyPublished";
import { emojiRoutes, emojiAdminRoutes } from "./routes/emoji";
import { questionRoutes } from "./routes/questions";
import { crashIngestRoutes } from "./routes/crashes";
import { telegramWebhookRoutes } from "./routes/telegramWebhook";
import { telegramMiniAppApi, telegramMiniAppPage } from "./routes/telegramMiniApp";
import { AssetStorage } from "./storage";
import { TelegramMediaVault, TelegramMediaVaultError } from "./telegramMediaVault";
import { AuthUser, Env } from "./types";
import { getAdminPermissions, verifyFirebaseIdToken } from "./auth";

type AppContext = Context<{ Bindings: Env; Variables: { user: AuthUser } }>;

const app = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

app.use("*", apiErrorContract);
app.use("*", operationMonitor);

// 1. CORS Middleware
app.use(
  "*",
  cors({
    origin: "*",
    allowMethods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allowHeaders: [
      "Authorization",
      "Content-Type",
      "x-file-name",
      "X-Eve-Client",
      "x-eve-client",
      "X-Diagnostic-Key",
      "x-diagnostic-key",
      "X-Telegram-Init-Data",
      "x-telegram-init-data",
      "X-Telegram-Bot-Api-Secret-Token",
    ],
    maxAge: 86400,
  })
);

// 2. Health check (Public allowlist)
app.get("/api/health", (c) => {
  return c.json({
    status: "ok",
    app: "Eve Mock Test API",
    version: "2.0.0",
    timestamp: Date.now(),
  });
});

// Public App Config (Force Update, Maintenance Mode & Announcement Channel Metadata)
app.get("/api/app-config", async (c) => {
  try {
    const cached = await matchStudentEdgeCache(c);
    if (cached) {
      return cached;
    }
    const config = await readAppConfig(getReadDb(c.env.DB));
    if (config.maintenanceMode) {
      c.header("Cache-Control", "no-store");
      return c.json({ success: true, data: config });
    }
    return await storeStudentEdgeCache(c, c.json({ success: true, data: config }));
  } catch {
    c.header("Cache-Control", "no-store");
    return c.json({ success: false, error: "App configuration unavailable" }, 503);
  }
});

// Public Announcement Channel Avatar Reader
app.get("/api/announcement-channel/avatar", async (c) => {
  try {
    const config = await readAppConfig(getReadDb(c.env.DB));
    return await serveStoredAvatarResponse(
      c,
      config.announcementChannelAvatarUrl,
      "channel-avatars/"
    );
  } catch {
    return c.json({ success: false, error: "Channel avatar unavailable" }, 404);
  }
});

// Public Floating Community Link
app.get("/api/floating-link", async (c) => {
  const db = getReadDb(c.env.DB);
  try {
    const row = await db.prepare("SELECT body FROM app_content WHERE id = 'floating_link'").first<{ body: string }>();
    if (!row || !row.body) {
      return c.json({ success: true, data: { url: "", x: null, y: null }, url: "" });
    }
    let url = "";
    let x: number | null = null;
    let y: number | null = null;
    const trimmed = row.body.trim();
    if (trimmed.startsWith("{")) {
      try {
        const parsed = JSON.parse(trimmed);
        url = typeof parsed.url === "string" ? parsed.url.trim() : "";
        x = typeof parsed.x === "number" && !isNaN(parsed.x) ? parsed.x : null;
        y = typeof parsed.y === "number" && !isNaN(parsed.y) ? parsed.y : null;
      } catch {
        url = trimmed;
      }
    } else {
      url = trimmed;
    }
    return c.json({ success: true, data: { url, x, y }, url, x, y });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

// These diagnostic routes are registered BEFORE the general /api/* auth
// middleware, so c.get("user") cannot establish Firebase identity here.
// Verify the token against Google's keys and fetch admin rights from D1.
async function isDiagnosticAuthorized(c: AppContext): Promise<boolean> {
  const authHeader = c.req.header("Authorization");
  const bearerToken = authHeader?.startsWith("Bearer ") ? authHeader.substring(7).trim() : null;
  const diagKey = c.req.header("X-Diagnostic-Key") || c.req.header("x-diagnostic-key") || bearerToken;
  if (diagKey && c.env.DIAGNOSTIC_KEY && diagKey.trim() === c.env.DIAGNOSTIC_KEY.trim()) {
    return true;
  }

  if (!bearerToken) return false;
  try {
    const identity = await verifyFirebaseIdToken(bearerToken, c.env.FIREBASE_PROJECT_ID);
    if (!identity.emailVerified || !identity.email) return false;
    const permissions = await getAdminPermissions(c.env.DB, identity.email);
    return permissions?.rights.includes("view_analytics_logs") === true;
  } catch {
    // Invalid or expired Firebase tokens and missing D1 rights fail closed.
    return false;
  }
}

// Diagnostic Handler for D1
async function handleD1Diagnostic(c: AppContext) {
  if (!await isDiagnosticAuthorized(c)) {
    return c.json(
      {
        success: false,
        error: "Unauthorized: Diagnostic operations require admin privileges or diagnostic secret key.",
      },
      401
    );
  }

  try {
    const db = c.env.DB;
    const { results: tables } = await db
      .prepare(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE '_cf_%' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'd1_%' ORDER BY name;"
      )
      .all<{ name: string }>();

    const { results: indexes } = await db
      .prepare("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%';")
      .all<{ name: string }>();

    const { results: triggers } = await db
      .prepare("SELECT name FROM sqlite_master WHERE type='trigger';")
      .all<{ name: string }>();

    // Test safe write & immediate cleanup
    const testId = `diag-${Date.now()}`;
    await db.prepare("INSERT INTO admins (email, created_at) VALUES (?, ?)").bind(testId, Date.now()).run();
    await db.prepare("DELETE FROM admins WHERE email = ?").bind(testId).run();

    return c.json({
      success: true,
      status: "connected",
      tableCount: (tables || []).length,
      tables: (tables || []).map((t: any) => t.name),
      indexCount: (indexes || []).length,
      triggerCount: (triggers || []).length,
      readWriteTest: "PASS",
    });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
}

// Diagnostic Handler for Telegram Media Vault / AssetStorage
async function handleStorageDiagnostic(c: AppContext) {
  if (!await isDiagnosticAuthorized(c)) {
    return c.json(
      {
        success: false,
        error: "Unauthorized: Diagnostic operations require admin privileges or diagnostic secret key.",
      },
      401
    );
  }

  const vault = new TelegramMediaVault(c.env);
  if (!c.env.EMOJI_BUCKET && !vault.isConfigured()) {
    return c.json(
      {
        success: false,
        error: "TELEGRAM_BOT_TOKEN and TELEGRAM_MEDIA_CHANNEL_ID are not configured on the Worker",
      },
      500
    );
  }

  try {
    const storage = new AssetStorage(c.env);
    const testId = `verify-${Date.now()}`;

    // 1. Banner upload & delete verification
    const bannerPath = `banners/test-${testId}.jpg`;
    const dummyBanner = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
    const bannerRef = await storage.uploadFile(bannerPath, dummyBanner, "image/jpeg");
    await storage.deleteFiles([bannerRef.startsWith("tg:") ? bannerRef : bannerPath]);

    // 2. Syllabus upload & delete verification
    const syllabusPath = `syllabi/test-${testId}.pdf`;
    const dummyPdf = new TextEncoder().encode("%PDF-1.4\n%Test Eve Syllabus\n%%EOF");
    const syllabusRef = await storage.uploadFile(syllabusPath, dummyPdf, "application/pdf");
    await storage.deleteFiles([syllabusRef.startsWith("tg:") ? syllabusRef : syllabusPath]);

    return c.json({
      success: true,
      connection: "PASS",
      backend: storage.getStorageBackendLabel(),
      bannerUpload: "PASS",
      bannerDelete: "PASS",
      syllabusUpload: "PASS",
      syllabusDelete: "PASS",
      sampleBannerRef: bannerRef,
      sampleSyllabusRef: syllabusRef,
    });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
}

// Diagnostic Endpoints (Protected: requires admin or X-Diagnostic-Key)
app.get("/api/diag/d1", handleD1Diagnostic);
app.get("/api/health/d1", handleD1Diagnostic);
app.get("/api/diag/storage", handleStorageDiagnostic);
app.get("/api/health/storage", handleStorageDiagnostic);

// 3. Layer 1: Per-IP Rate Limiting (300 req / 60s)
app.use("/api/*", ipRateLimit());

// 4. Authentication Middleware
app.use("/api/*", authMiddleware);
app.use("/api/*", adminJsonContract);

// 5. Layer 2: Per-User Rate Limiting (General 120/60s, submit 10, start 20, generate-now 5)
app.use("/api/*", userRateLimit());

// 5. Mount Domain Routers
app.route("/api/auth", authRoutes);
app.route("/api/users", authRoutes);
app.route("/api/exams", examRoutes);
app.route("/api/questions", questionRoutes);
app.route("/api/attempts", attemptRoutes);
app.route("/api/leaderboard", leaderboardRoutes);
app.route("/api/broadcasts", broadcastRoutes);
app.route("/api/feedback", feedbackRoutes);
app.route("/api/banners", bannerRoutes);
app.route("/api/pins", pinRoutes);
app.route("/api/bookmarks", bookmarkRoutes);
app.route("/api/polls", pollRoutes);
app.route("/api/app-content", appContentRoutes);
app.route("/api/admin/system", monitoringRoutes);
app.route("/api/admin", adminRoutes);
app.route("/api/generated-tests", generatedTestRoutes);
app.route("/api/premium", premiumRoutes);
app.route("/api/reports", reportRoutes);
app.route("/api/admin/reports", adminReportRoutes);
app.route("/api/admin/audit-log", adminAuditLogRoutes);
app.route("/api/admin/emoji", emojiAdminRoutes);
app.route("/api/emoji", emojiRoutes);
app.route("/api/crashes", crashIngestRoutes);
app.route("/api/typography", typographyRoutes);
app.route("/api/telegram/webhook", telegramWebhookRoutes);
app.route("/api/telegram/miniapp", telegramMiniAppApi);
app.route("/admin-miniapp", telegramMiniAppPage);

// 6. Global Error Handling & 404
app.notFound((c) => {
  return c.json({ success: false, error: `Route not found: ${c.req.method} ${c.req.path}` }, 404);
});

app.onError((err, c) => {
  if (err instanceof HTTPException) {
    return c.json({ success: false, error: err.status < 500 ? err.message : "Request protection is temporarily unavailable. Please retry." }, err.status);
  }
  if (err instanceof TelegramMediaVaultError && err.status === 400) {
    return c.json({ success: false, error: err.message }, 400);
  }
  console.error("Unhandled Worker failure", { name: err.name });
  return c.json({ success: false, error: "The server could not complete this operation. Please retry.", requestId: c.res.headers.get("X-Request-ID") }, 500);
});

export default {
  fetch: app.fetch,
  scheduled: handleScheduledTestGeneration,
};
