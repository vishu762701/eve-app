// ============================================================================
// Authentication & Admin Authorization Middleware
// ============================================================================

import { Context, Next } from "hono";
import { AdminRight, getAdminPermissions, isUserAdmin, verifyFirebaseIdToken } from "../auth";
import { AuthUser, Env } from "../types";
import { readAppConfig } from "../util/appConfig";

export function isStudentMaintenanceGuardedPath(path: string): boolean {
  return (
    path === "/api/exams" ||
    path.startsWith("/api/exams/") ||
    path === "/api/generated-tests" ||
    path.startsWith("/api/generated-tests/") ||
    path === "/api/attempts" ||
    path.startsWith("/api/attempts/") ||
    path === "/api/leaderboard" ||
    path.startsWith("/api/leaderboard/") ||
    path === "/api/polls" ||
    path.startsWith("/api/polls/")
  );
}

async function recordSecurityEvent(
  db: D1Database | undefined,
  operation: string,
  category: "AUTHENTICATION" | "AUTHORIZATION",
  status: number,
  path: string,
  detail: string
) {
  if (!db || !path.startsWith("/api/admin")) return;
  try {
    const safePath = path.slice(0, 80);
    const safeDetail = detail.slice(0, 80);
    const stmt = db
      .prepare(
        "INSERT INTO operation_events (id, operation, timestamp, category, status, retryable, correlation_id) VALUES (?, ?, ?, ?, ?, 0, ?)"
      )
      .bind(crypto.randomUUID(), operation, Date.now(), category, status, `path=${safePath}|${safeDetail}`);
    if (typeof stmt?.run === "function") {
      await stmt.run().catch(() => {});
    }
  } catch {
    // Telemetry must never fail or alter an auth response.
  }
}

export async function enforceStudentMaintenanceMode(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  user: AuthUser,
  path: string
) {
  if (!user.isAdmin && isStudentMaintenanceGuardedPath(path)) {
    try {
      const config = await readAppConfig(c.env.DB);
      if (config.maintenanceMode) {
        return c.json(
          {
            success: false,
            code: "MAINTENANCE_MODE",
            error: config.maintenanceMessage,
            maintenanceMessage: config.maintenanceMessage,
          },
          503
        );
      }
    } catch {
      // Keep API functional if optional config row is temporarily unreadable.
    }
  }
  return null;
}

export async function authMiddleware(c: Context<{ Bindings: Env; Variables: { user: AuthUser } }>, next: Next) {
  const path = c.req.path;
  const method = c.req.method;

  // Public allowlist - read-only / diagnostic endpoints handled with their own auth
  if (
    (path === "/api/health" && method === "GET") ||
    path.startsWith("/api/health/") ||
    path.startsWith("/api/diag/") ||
    path === "/api/app-config" ||
    path === "/api/floating-link" ||
    (path === "/api/premium/plan" && method === "GET") ||
    path === "/api/premium/webhook" ||
    (path.startsWith("/api/app-content/") && method === "GET") ||
    (path === "/api/banners" && method === "GET") ||
    (path.startsWith("/api/banners/") && path.endsWith("/media") && method === "GET") ||
    (path === "/api/typography/published" && method === "GET") ||
    (path.startsWith("/api/exams/") && path.endsWith("/avatar") && method === "GET") ||
    (path.startsWith("/api/generated-tests/") && path.endsWith("/avatar") && method === "GET") ||
    (path === "/api/announcement-channel/avatar" && method === "GET")
  ) {
    return next();
  }

  const authHeader = c.req.header("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    await recordSecurityEvent(c.env.DB, "admin_auth_probe", "AUTHENTICATION", 401, path, "missing_bearer");
    return c.json({ success: false, error: "Missing or malformed Authorization header" }, 401);
  }

  const token = authHeader.substring(7).trim();
  if (!token) {
    await recordSecurityEvent(c.env.DB, "admin_auth_probe", "AUTHENTICATION", 401, path, "empty_bearer");
    return c.json({ success: false, error: "Empty Bearer token" }, 401);
  }

  let user: AuthUser;
  try { user = await verifyFirebaseIdToken(token, c.env.FIREBASE_PROJECT_ID); }
  catch {
    await recordSecurityEvent(c.env.DB, "admin_auth_probe", "AUTHENTICATION", 401, path, "invalid_token");
    return c.json({ success: false, error: "Invalid or expired authentication. Please sign in again." }, 401);
  }
  try {
    // Double check dynamic admin list from D1
    const isAdmin = user.emailVerified === true && await isUserAdmin(c.env.DB, user.email);
    user.isAdmin = isAdmin;

    const profile = await c.env.DB.prepare("SELECT disabled FROM users WHERE id = ?").bind(user.uid).first<{ disabled?: number }>();
    if (profile?.disabled === 1) return c.json({ success: false, error: "This account is disabled. Contact an administrator." }, 403);
    c.set("user", user);

    // Server-side Maintenance Mode enforcement for student API endpoints (Admins/Owners always bypass).
    const maintenanceBlocked = await enforceStudentMaintenanceMode(c, user, path);
    if (maintenanceBlocked) {
      return maintenanceBlocked;
    }

    // Keep 'Online Now' truthful by updating last_active on authenticated activity (throttled to at most once per 2 minutes)
    try {
      const now = Date.now();
      const updateStmt = c.env.DB.prepare(
        "UPDATE users SET last_active = ?1 WHERE id = ?2 AND (?1 - COALESCE(last_active, 0)) > 120000"
      ).bind(now, user.uid);
      if (typeof updateStmt.run === "function") {
        updateStmt.run().catch(() => {});
      }
    } catch {}

    return next();
  } catch (err: any) {
    return c.json({ success: false, error: "Authentication services are temporarily unavailable. Please retry." }, 503);
  }
}

export async function requireAdmin(c: Context<{ Bindings: Env; Variables: { user: AuthUser } }>, next: Next) {
  const user = c.get("user");
  if (!user || !user.isAdmin) {
    await recordSecurityEvent(c.env.DB, "admin_access_denied", "AUTHORIZATION", 403, c.req.path, "not_admin");
    return c.json({ success: false, error: "Admin privileges required" }, 403);
  }
  return next();
}

export function requirePermission(permission: AdminRight) {
  return async (c: Context<{ Bindings: Env; Variables: { user: AuthUser } }>, next: Next) => {
    const user = c.get("user");
    if (!user || !user.isAdmin) {
      await recordSecurityEvent(c.env.DB, "admin_access_denied", "AUTHORIZATION", 403, c.req.path, `not_admin:${permission}`);
      return c.json({ success: false, error: "Admin privileges required" }, 403);
    }

    const perms = await getAdminPermissions(c.env.DB, user.email);
    if (perms) {
      if (!perms.rights.includes(permission)) {
        await recordSecurityEvent(c.env.DB, "admin_permission_denied", "AUTHORIZATION", 403, c.req.path, `missing:${permission}`);
        return c.json({ success: false, error: `Missing required permission: ${permission}` }, 403);
      }
      return next();
    }

    // Missing membership or an unavailable permission store must fail closed.
    // Tests must provide a real owner or explicit permissions, just like callers.
    return c.json({ success: false, error: "Admin permissions could not be verified" }, 403);
  };
}
