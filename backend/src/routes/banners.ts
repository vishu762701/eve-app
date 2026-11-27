import { cleanupMedia } from '../services/mediaCleanup';
// ============================================================================
// Home Promotional Banners (Image, GIF, WebP, MP4, WebM via R2 / Telegram Media Vault)
// ============================================================================

import { Hono } from "hono";
import { requirePermission } from "../middleware/authMiddleware";
import { AssetStorage } from "../storage";
import { AuthUser, Env, HomeBannerRow } from "../types";
import { prepareAdminAuditLog } from "../util/auditLog";
import { getReadDb } from "../util/d1Session";
import { matchStudentEdgeCache, purgeEdgeCache, storeStudentEdgeCache } from "../util/edgeCache";
import { serveCachedStorageMedia } from "../util/mediaCache";

export const bannerRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

const MAX_BANNER_IMAGE_BYTES = 5 * 1024 * 1024;
const MAX_BANNER_VIDEO_BYTES = 20 * 1024 * 1024;

function normalizeBannerLink(urlValue: unknown, labelValue: unknown): { url: string; label: string } | null {
  const urlText = typeof urlValue === "string" ? urlValue.trim() : (urlValue == null ? "" : null);
  const labelRaw = typeof labelValue === "string" ? labelValue : (labelValue == null ? "" : null);
  if (urlText === null || labelRaw === null) return null;

  const label = labelRaw.replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, 64);
  if (!urlText) {
    return { url: "", label };
  }
  if (urlText.length > 2048 || /[\u0000-\u0020\u007F]/.test(urlText)) return null;

  try {
    const url = new URL(urlText);
    if (
      (url.protocol !== "https:" && url.protocol !== "http:") ||
      !url.hostname ||
      url.username ||
      url.password
    ) return null;
    return { url: url.href, label };
  } catch (_error) {
    return null;
  }
}

async function getBannerColumnNames(db: D1Database): Promise<Set<string>> {
  const cols = new Set<string>();
  try {
    const { results } = await db.prepare("PRAGMA table_info(home_banners)").all<{ name: string }>();
    for (const row of results || []) {
      if (row?.name) cols.add(row.name.toLowerCase());
    }
  } catch {}
  return cols;
}

function inferBannerMediaType(row: Partial<HomeBannerRow>): "image" | "video" {
  const rawType = String(row.media_type || "").trim().toLowerCase();
  if (rawType === "video") return "video";
  if (rawType === "image") return "image";
  const hint = `${row.storage_path || ""} ${row.image_url || ""}`.toLowerCase();
  if (/\.(mp4|webm)(\?|\s|$)/i.test(hint)) return "video";
  return "image";
}

function detectBannerMedia(
  bytes: Uint8Array,
  declaredMime?: string
): { mime: string; ext: string; mediaType: "image" | "video" } | null {
  if (bytes.length === 0) return null;
  // JPEG
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return { mime: "image/jpeg", ext: "jpg", mediaType: "image" };
  }
  // PNG
  if (bytes.length >= 4 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
    return { mime: "image/png", ext: "png", mediaType: "image" };
  }
  // GIF
  if (bytes.length >= 4 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x38) {
    return { mime: "image/gif", ext: "gif", mediaType: "image" };
  }
  // WebP
  if (
    bytes.length >= 12 &&
    bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50
  ) {
    return { mime: "image/webp", ext: "webp", mediaType: "image" };
  }
  // MP4 ('ftyp' box at offset 4)
  if (bytes.length >= 8 && bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70) {
    return { mime: "video/mp4", ext: "mp4", mediaType: "video" };
  }
  // WebM / Matroska EBML header (1A 45 DF A3)
  if (bytes.length >= 4 && bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3) {
    return { mime: "video/webm", ext: "webm", mediaType: "video" };
  }

  const cleanMime = String(declaredMime || "").split(";")[0].trim().toLowerCase();
  if (cleanMime === "image/jpeg" || cleanMime === "image/jpg") return { mime: "image/jpeg", ext: "jpg", mediaType: "image" };
  if (cleanMime === "image/png") return { mime: "image/png", ext: "png", mediaType: "image" };
  if (cleanMime === "image/webp") return { mime: "image/webp", ext: "webp", mediaType: "image" };
  if (cleanMime === "image/gif") return { mime: "image/gif", ext: "gif", mediaType: "image" };
  if (cleanMime === "video/mp4") return { mime: "video/mp4", ext: "mp4", mediaType: "video" };
  if (cleanMime === "video/webm") return { mime: "video/webm", ext: "webm", mediaType: "video" };
  return null;
}

function mapBannerRow(r: HomeBannerRow) {
  return {
    id: r.id,
    imageUrl: r.image_url,
    storagePath: r.storage_path || null,
    mediaType: inferBannerMediaType(r),
    order: r.order_index,
    uploadedAt: r.uploaded_at,
    uploadedBy: r.uploaded_by,
    active: Boolean(r.active),
    linkUrl: r.link_url || "",
    linkLabel: r.link_label || "",
  };
}

// GET /api/banners - List active banners for home carousel
bannerRoutes.get("/", async (c) => {
  const cached = await matchStudentEdgeCache(c);
  if (cached) return cached;
  const db = getReadDb(c.env.DB);
  const { results } = await db
    .prepare("SELECT * FROM home_banners WHERE active = 1 ORDER BY order_index ASC")
    .all<HomeBannerRow>();
  return storeStudentEdgeCache(c, c.json({ success: true, data: (results || []).map(mapBannerRow) }));
});

// GET /api/banners/manage - List all banners (active & hidden) for Admin
bannerRoutes.get("/manage", requirePermission("manage_content"), async (c) => {
  const db = getReadDb(c.env.DB);
  const { results } = await db
    .prepare("SELECT * FROM home_banners ORDER BY order_index ASC")
    .all<HomeBannerRow>();
  return c.json({ success: true, data: (results || []).map(mapBannerRow) });
});

// GET /api/banners/:id/media - Public asset reader with Cloudflare Edge Cache
bannerRoutes.get("/:id/media", async (c) => {
  const id = c.req.param("id") || "";
  const db = getReadDb(c.env.DB);
  const row = await db
    .prepare("SELECT storage_path FROM home_banners WHERE id = ?")
    .bind(id)
    .first<{ storage_path?: string }>();
  const key = String(row?.storage_path || "").trim();
  if (!key) {
    return c.json({ success: false, error: "Banner media not found" }, 404);
  }
  return serveCachedStorageMedia(c, key, {
    notFoundMessage: "Banner media unavailable",
  });
});

// POST /api/banners - Upload banner image or video & record metadata (Admin)
bannerRoutes.post("/", requirePermission("manage_content"), async (c) => {
  const user = c.get("user");
  const contentType = (c.req.header("content-type") || "").toLowerCase();
  const db = c.env.DB;

  let mediaBytes: Uint8Array;
  let declaredMime = "";
  let linkUrl: unknown = "";
  let linkLabel: unknown = "";

  if (contentType.includes("multipart/form-data")) {
    const formData = await c.req.formData();
    const fileValue: unknown = formData.get("file");
    if (
      !fileValue ||
      typeof fileValue !== "object" ||
      !("arrayBuffer" in fileValue) ||
      typeof fileValue.arrayBuffer !== "function"
    ) {
      return c.json({ success: false, error: "No image or video file provided" }, 400);
    }
    const file = fileValue as File;
    mediaBytes = new Uint8Array(await file.arrayBuffer());
    declaredMime = file.type || "";
    linkUrl = formData.get("linkUrl") ?? "";
    linkLabel = formData.get("linkLabel") ?? "";
  } else if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
    declaredMime = contentType.split(";")[0];
    mediaBytes = new Uint8Array(await c.req.arrayBuffer());
  } else {
    const parsedBody = await c.req.json().catch(() => ({}));
    const body = parsedBody && typeof parsedBody === "object" && !Array.isArray(parsedBody)
      ? parsedBody as Record<string, unknown>
      : {};
    if (typeof body.base64 !== "string" || !body.base64.trim()) {
      return c.json({ success: false, error: "Image/video file or base64 required" }, 400);
    }
    const rawB64 = body.base64.trim();
    const commaIdx = rawB64.indexOf(",");
    const cleanB64 = rawB64.startsWith("data:") && commaIdx > 0 ? rawB64.slice(commaIdx + 1) : rawB64;
    if (cleanB64.length > Math.ceil(MAX_BANNER_VIDEO_BYTES * 4 / 3) + 16) {
      return c.json({ success: false, error: "Banner file exceeds maximum size limit" }, 400);
    }
    try {
      const binary = atob(cleanB64);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      mediaBytes = bytes;
    } catch {
      return c.json({ success: false, error: "Invalid base64 banner payload" }, 400);
    }
    declaredMime = typeof body.mimeType === "string" ? body.mimeType : "";
    linkUrl = body.linkUrl ?? "";
    linkLabel = body.linkLabel ?? "";
  }

  const detected = detectBannerMedia(mediaBytes, declaredMime);
  if (!detected) {
    return c.json({
      success: false,
      error: "Supported banner formats: JPEG, PNG, WebP, GIF (max 5MB) or MP4, WebM video (max 20MB)",
    }, 400);
  }
  const maxBytes = detected.mediaType === "video" ? MAX_BANNER_VIDEO_BYTES : MAX_BANNER_IMAGE_BYTES;
  if (mediaBytes.byteLength > maxBytes) {
    return c.json({
      success: false,
      error: detected.mediaType === "video"
        ? "Banner video exceeds 20MB limit"
        : "Banner image exceeds 5MB limit",
    }, 400);
  }

  const link = normalizeBannerLink(linkUrl, linkLabel);
  if (!link) {
    return c.json({ success: false, error: "Provide a valid HTTP(S) link URL or leave it empty" }, 400);
  }

  const id = crypto.randomUUID();
  const storage = new AssetStorage(c.env);
  const storagePath = `banners/${id}.${detected.ext}`;
  const storedLocation = await storage.uploadFile(storagePath, mediaBytes, detected.mime);
  const persistedStoragePath = storedLocation.startsWith("tg:") ? storedLocation : storagePath;
  const imageUrl = `/api/banners/${encodeURIComponent(id)}/media?v=${Date.now()}`;

  const maxOrderRow = await db
    .prepare("SELECT MAX(order_index) as max_order FROM home_banners")
    .first<{ max_order: number | null }>();
  const nextOrder = (maxOrderRow?.max_order ?? -1) + 1;
  const now = Date.now();

  const cols = await getBannerColumnNames(db);
  const hasMediaType = cols.has("media_type");

  const insertStmt = hasMediaType
    ? db
        .prepare(
          `INSERT INTO home_banners (
             id, image_url, storage_path, order_index, uploaded_at, uploaded_by, active, link_url, link_label, media_type
           ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`
        )
        .bind(id, imageUrl, persistedStoragePath, nextOrder, now, user.email, link.url, link.label, detected.mediaType)
    : db
        .prepare(
          `INSERT INTO home_banners (
             id, image_url, storage_path, order_index, uploaded_at, uploaded_by, active, link_url, link_label
           ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)`
        )
        .bind(id, imageUrl, persistedStoragePath, nextOrder, now, user.email, link.url, link.label);

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_ADDED",
    description: `Added ${detected.mediaType} banner '${id}'`,
    targetType: "banner",
    targetId: id,
    metadata: { order: nextOrder, mediaType: detected.mediaType, linkUrl: link.url, linkLabel: link.label },
  });

  try {
    await db.batch([insertStmt, auditStmt]);
  } catch (err) {
    try { await storage.deleteFiles([persistedStoragePath]); } catch {}
    throw err;
  }
  await purgeEdgeCache(c, ["/api/banners"]);

  return c.json(
    {
      success: true,
      data: {
        id,
        imageUrl,
        storagePath: persistedStoragePath,
        mediaType: detected.mediaType,
        order: nextOrder,
        uploadedAt: now,
        uploadedBy: user.email,
        active: true,
        linkUrl: link.url,
        linkLabel: link.label,
      },
    },
    201
  );
});

// PUT /api/banners/:id/link - Set or clear an existing banner's optional CTA link & label
bannerRoutes.put("/:id/link", requirePermission("manage_content"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const body = await c.req.json().catch(() => ({})) as Record<string, unknown>;
  const link = normalizeBannerLink(body.linkUrl, body.linkLabel);
  if (!link) {
    return c.json({ success: false, error: "Provide a valid HTTP(S) link URL or leave it empty" }, 400);
  }

  const db = c.env.DB;
  const current = await db.prepare("SELECT id FROM home_banners WHERE id = ?").bind(id).first<{ id: string }>();
  if (!current) return c.json({ success: false, error: "Banner not found" }, 404);

  const updateStmt = db.prepare("UPDATE home_banners SET link_url = ?, link_label = ? WHERE id = ?")
    .bind(link.url, link.label, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_LINK_UPDATED",
    description: `Updated banner link for '${id}'`,
    targetType: "banner",
    targetId: id,
    metadata: { linkUrl: link.url, linkLabel: link.label },
  });
  await db.batch([updateStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/banners"]);

  return c.json({ success: true, data: { linkUrl: link.url, linkLabel: link.label } });
});

// PUT /api/banners/:id/status - Toggle banner active/hidden visibility (Admin)
bannerRoutes.put("/:id/status", requirePermission("manage_content"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const body = await c.req.json().catch(() => null) as Record<string, unknown> | null;
  if (!body || typeof body.active !== "boolean") {
    return c.json({ success: false, error: "active boolean is required" }, 400);
  }
  const active = body.active ? 1 : 0;
  const db = c.env.DB;

  const updateStmt = db.prepare("UPDATE home_banners SET active = ? WHERE id = ?").bind(active, id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_STATUS_UPDATED",
    description: `${active ? "Activated" : "Hidden"} banner '${id}'`,
    targetType: "banner",
    targetId: id,
    metadata: { active: Boolean(active) },
    condition: { sql: "changes() = 1", bindings: [] },
  });
  const results = await db.batch([updateStmt, auditStmt]);
  if (results[0]?.meta?.changes !== 1) {
    return c.json({ success: false, error: "Banner not found" }, 404);
  }
  await purgeEdgeCache(c, ["/api/banners"]);
  return c.json({ success: true, data: { id, active: Boolean(active) } });
});

// DELETE /api/banners/:id - Delete banner and purge file (Admin)
bannerRoutes.delete("/:id", requirePermission("manage_content"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const db = c.env.DB;

  const banner = await db
    .prepare("SELECT storage_path, image_url FROM home_banners WHERE id = ?")
    .bind(id)
    .first<HomeBannerRow>();

  if (!banner) return c.json({ success: false, error: 'Banner not found' }, 404);

  const deleteStmt = db.prepare("DELETE FROM home_banners WHERE id = ?").bind(id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_DELETED",
    description: `Deleted banner '${id}'`,
    targetType: "banner",
    targetId: id,
  });
  await db.batch([deleteStmt, auditStmt]);
  await purgeEdgeCache(c, ["/api/banners"]);

  const storagePath = String(banner.storage_path || "").trim();
  if (storagePath && !await cleanupMedia(c.env, storagePath, c.res.headers.get('X-Request-ID'))) {
    return c.json({ success: true, message: 'Banner removed. Media cleanup was not completed; check System Monitor.' });
  }
  return c.json({ success: true });
});

// PUT /api/banners/:id/reorder - Swap order with adjacent banner (Admin)
bannerRoutes.put("/:id/reorder", requirePermission("manage_content"), async (c) => {
  const user = c.get("user");
  const id = c.req.param("id") || "";
  const body = await c.req.json().catch(() => ({}));
  const moveUp = Boolean(body.moveUp);
  const db = c.env.DB;

  const { results: banners } = await db
    .prepare("SELECT id, order_index FROM home_banners ORDER BY order_index ASC")
    .all<{ id: string; order_index: number }>();

  if (!banners || banners.length < 2) {
    return c.json({ success: true });
  }

  const index = banners.findIndex((b) => b.id === id);
  if (index === -1) {
    return c.json({ success: false, error: "Banner not found" }, 404);
  }

  const targetIndex = moveUp ? index - 1 : index + 1;
  if (targetIndex < 0 || targetIndex >= banners.length) {
    return c.json({ success: true });
  }

  const current = banners[index];
  const target = banners[targetIndex];

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "BANNER_REORDERED",
    description: `Reordered banner '${id}' (${moveUp ? "up" : "down"})`,
    targetType: "banner",
    targetId: id,
    metadata: { fromOrder: current.order_index, toOrder: target.order_index },
  });

  await db.batch([
    db.prepare("UPDATE home_banners SET order_index = ? WHERE id = ?").bind(target.order_index, current.id),
    db.prepare("UPDATE home_banners SET order_index = ? WHERE id = ?").bind(current.order_index, target.id),
    auditStmt,
  ]);
  await purgeEdgeCache(c, ["/api/banners"]);

  return c.json({ success: true });
});
