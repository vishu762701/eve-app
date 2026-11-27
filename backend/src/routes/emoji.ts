import { cleanupEmojiMedia } from "../services/mediaCleanup";
// ============================================================================
// Animated Emoji & Sticker Pack Library Routes
// Supports Cloudflare R2 (preferred) + Telegram Media Vault fallback,
// D1 pack catalogue metadata, Worker asset delivery, and rights-gated import.
// ============================================================================

import { Hono } from "hono";
import { requirePermission } from "../middleware/authMiddleware";
import { AssetStorage } from "../storage";
import { isUserPremium } from "./premium";
import { AuthUser, EmojiItemRow, EmojiPackRow, EmojiPlacementPublic, Env } from "../types";
import { prepareAdminAuditLog, recordAdminAction } from "../util/auditLog";
import { serveCachedStorageMedia } from "../util/mediaCache";

export const emojiRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();
export const emojiAdminRoutes = new Hono<{ Bindings: Env; Variables: { user: AuthUser } }>();

const MAX_ANIMATION_SIZE_BYTES = 300 * 1024; // 300 KB cap for Lottie JSON
const MAX_VIDEO_STICKER_BYTES = 512 * 1024; // 512 KB cap for WebM video stickers
const VALID_APPROVED_RIGHTS_BASES = new Set([
  "eve_owned",
  "open_license",
  "creator_permitted",
  "rights_holder_authorized",
]);
const LIMITED_RIGHTS_BASES = new Set(["no_redistribution_rights", "limited_external"]);

async function saveImportedItem(
  db: D1Database,
  env: Env,
  user: AuthUser,
  itemId: string,
  storageKey: string,
  statement: D1PreparedStatement
): Promise<void> {
  const old = await db
    .prepare("SELECT storage_key FROM emoji_items WHERE id = ?")
    .bind(itemId)
    .first<{ storage_key: string }>();
  const { stmt: audit } = prepareAdminAuditLog(db, user, {
    actionType: "IMPORT_EMOJI_ITEM",
    description: "Imported emoji/sticker asset",
    targetType: "emoji_item",
    targetId: itemId,
  });
  try {
    await db.batch([statement, audit]);
  } catch (error) {
    await cleanupEmojiMedia(env, [storageKey]);
    throw error;
  }
  if (old && old.storage_key !== storageKey) {
    await cleanupEmojiMedia(env, [old.storage_key]);
  }
}

export async function sha256Hex(data: ArrayBuffer | Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", data);
  const bytes = new Uint8Array(digest);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, "0");
  }
  return hex;
}

export async function decompressGzipWithGuard(
  compressedBytes: Uint8Array,
  maxBytes: number = MAX_ANIMATION_SIZE_BYTES
): Promise<string> {
  const ds = new DecompressionStream("gzip");
  const writer = ds.writable.getWriter();
  writer.write(compressedBytes);
  writer.close();

  const reader = ds.readable.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      totalBytes += value.length;
      if (totalBytes > maxBytes) {
        throw new Error(`Decompressed animation exceeds maximum allowed size of ${maxBytes} bytes`);
      }
      chunks.push(value);
    }
  }

  const result = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return new TextDecoder().decode(result);
}

export function validateLottieJson(jsonStr: string): any {
  let parsed: any;
  try {
    parsed = JSON.parse(jsonStr);
  } catch (_e) {
    throw new Error("Invalid JSON: failed to parse animation file");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Invalid Lottie: root must be an object");
  }
  if (!("layers" in parsed) && !("v" in parsed)) {
    throw new Error("Invalid Lottie schema: missing layers or version property");
  }
  if (Array.isArray(parsed.assets)) {
    for (const asset of parsed.assets) {
      if (!asset || typeof asset !== "object") throw new Error("Invalid Lottie asset.");
      for (const value of [asset.u, asset.p]) {
        if (
          typeof value === "string" &&
          !value.startsWith("data:") &&
          (/^[a-z][a-z0-9+.-]*:/i.test(value) || value.startsWith("//"))
        ) {
          throw new Error("Security check failed: external network references in Lottie assets are prohibited");
        }
      }
    }
  }
  return parsed;
}

export function validateWebmBytes(bytes: Uint8Array): void {
  if (!bytes || bytes.length < 4 || bytes.length > MAX_VIDEO_STICKER_BYTES) {
    throw new Error(`WebM sticker must be between 4 bytes and ${MAX_VIDEO_STICKER_BYTES} bytes.`);
  }
  // EBML header magic: 0x1A 0x45 0xDF 0xA3
  if (bytes[0] !== 0x1a || bytes[1] !== 0x45 || bytes[2] !== 0xdf || bytes[3] !== 0xa3) {
    throw new Error("Invalid WebM video sticker bytes (missing EBML header).");
  }
}

async function isGlobalTelegramEmojiEnabled(db: D1Database): Promise<boolean> {
  try {
    const row = await db
      .prepare("SELECT body FROM app_content WHERE id = 'telegram_emoji_global'")
      .first<{ body: string }>();
    if (row && row.body) {
      const cfg = JSON.parse(row.body);
      if (cfg.enabled === false) {
        return false;
      }
    }
  } catch {}
  return true;
}

function buildItemAssetUrl(storage: AssetStorage, item: { id: string; storage_key: string }): string {
  if (item.storage_key.startsWith("res:raw/")) {
    return item.storage_key;
  }
  return storage.hasR2()
    ? `/api/emoji/assets/${encodeURIComponent(item.id)}`
    : storage.getPublicOrWorkerUrl(item.id, item.storage_key);
}

export async function getActiveEmojiPlacements(
  db: D1Database,
  env: Env
): Promise<{
  examPlacements: Map<string, EmojiPlacementPublic>;
  categoryPlacements: Record<string, EmojiPlacementPublic>;
  broadcastPlacements: Map<string, EmojiPlacementPublic>;
}> {
  const globalTelegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const storage = new AssetStorage(env);

  const query = `
    SELECT 
      ep.slot_type, ep.target_id,
      ei.id as item_id, ei.alt_emoji, ei.kind, ei.storage_key, ei.source as item_source,
      p.enabled as pack_enabled, ei.enabled as item_enabled
    FROM emoji_placements ep
    JOIN emoji_items ei ON ep.item_id = ei.id
    JOIN emoji_packs p ON ei.pack_id = p.id
    WHERE ei.enabled = 1 AND p.enabled = 1
  `;
  const { results } = await db.prepare(query).all<any>();

  const examPlacements = new Map<string, EmojiPlacementPublic>();
  const categoryPlacements: Record<string, EmojiPlacementPublic> = {};
  const broadcastPlacements = new Map<string, EmojiPlacementPublic>();

  for (const r of results || []) {
    if (!globalTelegramEnabled && (r.item_source === "telegram" || r.item_source === "telegram-ui")) {
      continue;
    }
    const url = buildItemAssetUrl(storage, { id: r.item_id, storage_key: r.storage_key });
    const publicItem: EmojiPlacementPublic = {
      id: r.item_id,
      url,
      kind: r.kind,
      alt: r.alt_emoji,
    };

    if (r.slot_type === "exam_card") {
      examPlacements.set(r.target_id, publicItem);
    } else if (r.slot_type === "category_header") {
      categoryPlacements[r.target_id] = publicItem;
    } else if (r.slot_type === "broadcast") {
      broadcastPlacements.set(r.target_id, publicItem);
    }
  }

  return { examPlacements, categoryPlacements, broadcastPlacements };
}

// ============================================================================
// Student & Public Pack Library Endpoints (/api/emoji/*)
// ============================================================================

emojiRoutes.get("/placements/public", async (c) => {
  const db = c.env.DB;
  const { examPlacements, categoryPlacements, broadcastPlacements } = await getActiveEmojiPlacements(db, c.env);

  const examMap: Record<string, EmojiPlacementPublic> = {};
  examPlacements.forEach((v, k) => {
    examMap[k] = v;
  });

  const broadcastMap: Record<string, EmojiPlacementPublic> = {};
  broadcastPlacements.forEach((v, k) => {
    broadcastMap[k] = v;
  });

  return c.json({
    success: true,
    data: {
      examPlacements: examMap,
      categoryPlacements,
      broadcastPlacements: broadcastMap,
    },
  });
});

emojiRoutes.get("/items/public", async (c) => {
  const offset = Number(c.req.query("offset") ?? "0");
  const limit = Number(c.req.query("limit") ?? "24");
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    return c.json({ success: false, error: "Invalid pagination" }, 400);
  }
  const db = c.env.DB;
  const telegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const sourceFilter = telegramEnabled ? "" : " AND i.source NOT IN ('telegram', 'telegram-ui')";
  const query = `SELECT i.id, i.pack_id, p.title AS pack_title, p.name AS pack_slug,
    i.alt_emoji, i.kind, i.storage_key, i.sha256, i.source, i.created_at, i.premium_origin
    FROM emoji_items i JOIN emoji_packs p ON i.pack_id = p.id
    WHERE i.enabled = 1 AND p.enabled = 1 AND i.kind = 'lottie'${sourceFilter}
    ORDER BY i.created_at DESC, i.id DESC LIMIT ? OFFSET ?`;
  const rows = await db.prepare(query).bind(limit, offset).all<{
    id: string;
    pack_id: string;
    pack_title: string;
    pack_slug: string;
    alt_emoji: string;
    kind: string;
    storage_key: string;
    sha256: string | null;
    source: string;
    created_at: number;
    premium_origin: number;
  }>();
  const storage = new AssetStorage(c.env);
  const data = (rows.results ?? []).map((item) => ({
    id: item.id,
    packId: item.pack_id,
    packTitle: item.pack_title,
    packSlug: item.pack_slug,
    altEmoji: item.alt_emoji,
    kind: item.kind,
    url: buildItemAssetUrl(storage, item),
    sha256: item.sha256,
    source: item.source,
    createdAt: item.created_at,
    enabled: true,
    premiumOrigin: Boolean(item.premium_origin),
  }));
  return c.json({ success: true, data, offset, limit });
});

// GET /api/emoji/packs - Complete published Emoji & Sticker pack catalogue with search, category & preview strips
emojiRoutes.get("/packs", async (c) => {
  const db = c.env.DB;
  const user = c.get("user");
  const packType = (c.req.query("type") || "all").trim().toLowerCase();
  const q = (c.req.query("q") || "").trim();
  const category = (c.req.query("category") || "").trim();
  const featured = c.req.query("featured");
  const offset = Number(c.req.query("offset") ?? "0");
  const limit = Number(c.req.query("limit") ?? "50");

  if (!["all", "emoji", "sticker"].includes(packType)) {
    return c.json({ success: false, error: "type must be emoji, sticker, or all" }, 400);
  }
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    return c.json({ success: false, error: "Invalid pagination" }, 400);
  }

  const telegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const userHasPremium = Boolean(user?.isAdmin) || (user?.uid ? await isUserPremium(db, user.uid).catch(() => false) : false);
  const storage = new AssetStorage(c.env);

  let sql = `
    SELECT p.*, COUNT(CASE WHEN i.enabled = 1 THEN 1 END) AS item_count
    FROM emoji_packs p
    LEFT JOIN emoji_items i ON i.pack_id = p.id
    WHERE p.enabled = 1
      AND COALESCE(p.publication_status, 'published') = 'published'
      AND COALESCE(p.rights_status, 'approved') = 'approved'
  `;
  const params: any[] = [];
  if (!telegramEnabled) {
    sql += " AND p.source NOT IN ('telegram', 'telegram-ui')";
  }
  if (packType !== "all") {
    sql += " AND COALESCE(p.pack_type, 'emoji') = ?";
    params.push(packType);
  }
  if (category) {
    sql += " AND LOWER(COALESCE(p.category, 'General')) = LOWER(?)";
    params.push(category);
  }
  if (featured === "1" || featured === "true") {
    sql += " AND COALESCE(p.featured, 0) = 1";
  }
  if (q) {
    sql += " AND (p.title LIKE ? OR p.name LIKE ? OR COALESCE(p.description, '') LIKE ? OR COALESCE(p.category, '') LIKE ?)";
    const wild = `%${q}%`;
    params.push(wild, wild, wild, wild);
  }
  sql += " GROUP BY p.id HAVING item_count > 0 ORDER BY COALESCE(p.featured, 0) DESC, p.created_at DESC, p.id DESC LIMIT ? OFFSET ?";
  params.push(limit, offset);

  const { results: packs } = await db.prepare(sql).bind(...params).all<any>();
  const packList = packs || [];

  const packIds = packList.map((p) => p.id);
  const previewsByPack = new Map<string, any[]>();
  if (packIds.length > 0) {
    const previewQuery = `
      SELECT id, pack_id, alt_emoji, kind, storage_key, thumbnail_key, sha256, byte_size,
             COALESCE(file_format, CASE WHEN kind = 'lottie' THEN 'lottie' ELSE 'webp' END) AS file_format,
             COALESCE(mime_type, 'application/json') AS mime_type,
             COALESCE(is_animated, CASE WHEN kind = 'lottie' THEN 1 ELSE 0 END) AS is_animated,
             COALESCE(is_video, CASE WHEN kind = 'video' THEN 1 ELSE 0 END) AS is_video
      FROM emoji_items
      WHERE enabled = 1 AND pack_id IN (SELECT value FROM json_each(?))
      ORDER BY COALESCE(item_index, 0) ASC, created_at ASC, id ASC
    `;
    const { results: itemRows } = await db.prepare(previewQuery).bind(JSON.stringify(packIds)).all<any>();
    for (const r of itemRows || []) {
      const list = previewsByPack.get(r.pack_id) || [];
      if (list.length < 6) {
        list.push({
          id: r.id,
          altEmoji: r.alt_emoji,
          kind: r.kind,
          fileFormat: r.file_format,
          mimeType: r.mime_type,
          isAnimated: Boolean(r.is_animated),
          isVideo: Boolean(r.is_video),
          byteSize: Number(r.byte_size || 0),
          sha256: r.sha256 || "",
          assetUrl: buildItemAssetUrl(storage, r),
        });
        previewsByPack.set(r.pack_id, list);
      }
    }
  }

  const data = packList.map((p) => {
    const tier = (p.access_tier || "free").toLowerCase();
    const isLockedForUser = tier === "premium" && !userHasPremium;
    const previews = previewsByPack.get(p.id) || [];
    const thumbnailUrl = p.thumbnail_key
      ? (String(p.thumbnail_key).startsWith("res:raw/") ? p.thumbnail_key : `/api/emoji/assets/${encodeURIComponent(p.id)}?variant=pack_thumb`)
      : (previews[0]?.assetUrl || "");

    return {
      id: p.id,
      name: p.name,
      title: p.title,
      packType: p.pack_type || "emoji",
      description: p.description || "",
      category: p.category || "General",
      featured: Boolean(p.featured),
      itemCount: Number(p.item_count || 0),
      version: Number(p.version || 1),
      accessTier: tier,
      isLockedForUser,
      rightsStatus: p.rights_status || "approved",
      creatorAttribution: p.creator_attribution || "",
      licenseAttribution: p.license_attribution || "",
      source: p.source,
      thumbnailUrl,
      previews,
      createdAt: p.created_at,
      updatedAt: p.updated_at || p.created_at,
    };
  });

  return c.json({ success: true, data, offset, limit });
});

// GET /api/emoji/packs/:id - Pack detail with ordered items
emojiRoutes.get("/packs/:id", async (c) => {
  const db = c.env.DB;
  const user = c.get("user");
  const id = c.req.param("id");
  const telegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const storage = new AssetStorage(c.env);

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (
    !pack ||
    !pack.enabled ||
    (pack.publication_status || "published") !== "published" ||
    (pack.rights_status || "approved") !== "approved" ||
    (!telegramEnabled && (pack.source === "telegram" || pack.source === "telegram-ui"))
  ) {
    return c.json({ success: false, error: "Pack not found" }, 404);
  }

  const userHasPremium = Boolean(user?.isAdmin) || (user?.uid ? await isUserPremium(db, user.uid).catch(() => false) : false);
  const accessTier = (pack.access_tier || "free").toLowerCase();
  const isLockedForUser = accessTier === "premium" && !userHasPremium;

  const { results } = await db
    .prepare("SELECT * FROM emoji_items WHERE pack_id = ? AND enabled = 1 ORDER BY COALESCE(item_index, 0) ASC, created_at ASC, id ASC")
    .bind(id)
    .all<EmojiItemRow>();

  const items = (results || []).map((r, idx) => ({
    id: r.id,
    packId: r.pack_id,
    index: r.item_index ?? idx,
    altEmoji: r.alt_emoji,
    kind: r.kind,
    fileFormat: r.file_format || (r.kind === "lottie" ? "lottie" : r.kind === "video" ? "webm" : "webp"),
    mimeType: r.mime_type || (r.kind === "lottie" ? "application/json" : r.kind === "video" ? "video/webm" : "image/webp"),
    isAnimated: Boolean(r.is_animated ?? (r.kind === "lottie" || r.kind === "video" ? 1 : 0)),
    isVideo: Boolean(r.is_video ?? (r.kind === "video" ? 1 : 0)),
    byteSize: Number(r.byte_size || 0),
    sha256: r.sha256 || "",
    assetUrl: isLockedForUser ? "" : buildItemAssetUrl(storage, r),
    previewUrl: idx < 6 || !isLockedForUser ? buildItemAssetUrl(storage, r) : "",
  }));

  return c.json({
    success: true,
    data: {
      id: pack.id,
      name: pack.name,
      title: pack.title,
      packType: pack.pack_type || "emoji",
      description: pack.description || "",
      category: pack.category || "General",
      featured: Boolean(pack.featured),
      itemCount: items.length,
      version: Number(pack.version || 1),
      accessTier,
      isLockedForUser,
      rightsStatus: pack.rights_status || "approved",
      creatorAttribution: pack.creator_attribution || "",
      licenseAttribution: pack.license_attribution || "",
      source: pack.source,
      items,
    },
  });
});

// GET /api/emoji/packs/:id/manifest - Authoritative offline install manifest with SHA-256 hashes
emojiRoutes.get("/packs/:id/manifest", async (c) => {
  const db = c.env.DB;
  const user = c.get("user");
  const id = c.req.param("id");
  const telegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const storage = new AssetStorage(c.env);

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (
    !pack ||
    !pack.enabled ||
    (pack.publication_status || "published") !== "published" ||
    (pack.rights_status || "approved") !== "approved" ||
    (!telegramEnabled && (pack.source === "telegram" || pack.source === "telegram-ui"))
  ) {
    return c.json({ success: false, error: "Pack not found" }, 404);
  }

  const accessTier = (pack.access_tier || "free").toLowerCase();
  if (accessTier === "premium" && !user?.isAdmin) {
    const hasPremium = Boolean(user?.isAdmin) || (user?.uid ? await isUserPremium(db, user.uid).catch(() => false) : false);
    if (!hasPremium) {
      return c.json({ success: false, code: "EVE_PREMIUM_REQUIRED", error: "Unlock EVE Premium to install this pack." }, 403);
    }
  }

  const { results } = await db
    .prepare("SELECT * FROM emoji_items WHERE pack_id = ? AND enabled = 1 ORDER BY COALESCE(item_index, 0) ASC, created_at ASC, id ASC")
    .bind(id)
    .all<EmojiItemRow>();

  let totalBytes = 0;
  const items = (results || []).map((r, idx) => {
    totalBytes += Number(r.byte_size || 0);
    return {
      id: r.id,
      index: r.item_index ?? idx,
      altEmoji: r.alt_emoji,
      kind: r.kind,
      fileFormat: r.file_format || (r.kind === "lottie" ? "lottie" : r.kind === "video" ? "webm" : "webp"),
      mimeType: r.mime_type || (r.kind === "lottie" ? "application/json" : r.kind === "video" ? "video/webm" : "image/webp"),
      byteSize: Number(r.byte_size || 0),
      sha256: r.sha256 || "",
      assetUrl: buildItemAssetUrl(storage, r),
    };
  });

  const manifestSha256 = await sha256Hex(
    new TextEncoder().encode(JSON.stringify(items.map((x) => `${x.id}:${x.sha256}:${x.byteSize}`)))
  );

  return c.json({
    success: true,
    data: {
      packId: pack.id,
      name: pack.name,
      title: pack.title,
      packType: pack.pack_type || "emoji",
      version: Number(pack.version || 1),
      itemCount: items.length,
      totalBytes,
      manifestSha256,
      items,
    },
  });
});

// GET /api/emoji/assets/:id - Serve approved R2/Storage asset bytes through Worker
emojiRoutes.get("/assets/:id", async (c) => {
  const db = c.env.DB;
  const user = c.get("user");
  const id = c.req.param("id");
  const variant = c.req.query("variant") || "";
  const telegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const storage = new AssetStorage(c.env);

  if (variant === "pack_thumb") {
    const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
    if (
      !pack ||
      !pack.enabled ||
      (pack.publication_status || "published") !== "published" ||
      (pack.rights_status || "approved") !== "approved" ||
      !pack.thumbnail_key ||
      (!telegramEnabled && (pack.source === "telegram" || pack.source === "telegram-ui"))
    ) {
      return c.json({ success: false, error: "Pack thumbnail not found" }, 404);
    }
    return serveCachedStorageMedia(c, pack.thumbnail_key, {
      notFoundMessage: "Thumbnail asset missing",
    });
  }

  const row = await db
    .prepare(
      `SELECT i.*, p.enabled AS pack_enabled, p.publication_status, p.rights_status, p.access_tier, p.source AS pack_source
       FROM emoji_items i JOIN emoji_packs p ON i.pack_id = p.id
       WHERE i.id = ?`
    )
    .bind(id)
    .first<any>();

  if (
    !row ||
    !row.enabled ||
    !row.pack_enabled ||
    (row.publication_status || "published") !== "published" ||
    (row.rights_status || "approved") !== "approved" ||
    (!telegramEnabled && (row.source === "telegram" || row.source === "telegram-ui"))
  ) {
    return c.json({ success: false, error: "Asset not found" }, 404);
  }

  if ((row.access_tier || "free").toLowerCase() === "premium" && !user?.isAdmin) {
    const hasPremium = Boolean(user?.isAdmin) || (user?.uid ? await isUserPremium(db, user.uid).catch(() => false) : false);
    if (!hasPremium) {
      return c.json({ success: false, code: "EVE_PREMIUM_REQUIRED", error: "EVE Premium required for this pack." }, 403);
    }
  }

  if (String(row.storage_key || "").startsWith("res:raw/")) {
    return c.json({ success: true, data: { resourceRef: row.storage_key } });
  }

  return serveCachedStorageMedia(c, row.storage_key, {
    notFoundMessage: "Asset missing in storage",
    fallbackContentType: row.mime_type || "application/octet-stream",
    extraHeaders: row.sha256
      ? { "X-Asset-Sha256": row.sha256, ETag: `"${row.sha256}"` }
      : undefined,
  });
});

// ============================================================================
// Protected Admin Emoji & Sticker Endpoints (requirePermission("manage_content"))
// ============================================================================

emojiAdminRoutes.use("/*", requirePermission("manage_content"));

emojiAdminRoutes.get("/packs", async (c) => {
  const db = c.env.DB;
  const globalTelegramEnabled = await isGlobalTelegramEmojiEnabled(db);
  const storage = new AssetStorage(c.env);

  const query = `
    SELECT p.*, COUNT(i.id) as item_count
    FROM emoji_packs p
    LEFT JOIN emoji_items i ON p.id = i.pack_id
    GROUP BY p.id
    ORDER BY p.created_at DESC
  `;
  const { results } = await db.prepare(query).all<any>();

  const list = (results || []).map((r) => ({
    id: r.id,
    name: r.name,
    title: r.title,
    packType: r.pack_type || "emoji",
    description: r.description || "",
    category: r.category || "General",
    featured: Boolean(r.featured),
    version: Number(r.version || 1),
    accessTier: r.access_tier || "free",
    rightsStatus: r.rights_status || "approved",
    creatorAttribution: r.creator_attribution || "",
    licenseAttribution: r.license_attribution || "",
    licenseEvidence: r.license_evidence || "",
    publicationStatus: r.publication_status || "published",
    importStatus: r.import_status || "complete",
    importExpectedCount: Number(r.import_expected_count || 0),
    importFailedCount: Number(r.import_failed_count || 0),
    importErrorSummary: r.import_error_summary || "",
    thumbnailUrl: r.thumbnail_key
      ? (String(r.thumbnail_key).startsWith("res:raw/") ? r.thumbnail_key : `/api/emoji/assets/${encodeURIComponent(r.id)}?variant=pack_thumb`)
      : "",
    source: r.source,
    enabled: Boolean(r.enabled),
    itemCount: Number(r.item_count || 0),
    createdAt: r.created_at,
    updatedAt: r.updated_at || r.created_at,
    createdBy: r.created_by || "",
    storageBackend: storage.getStorageBackendLabel(),
  }));

  return c.json({
    success: true,
    data: {
      packs: list,
      globalTelegramEnabled,
      telegramBotConfigured: Boolean(c.env.TELEGRAM_BOT_TOKEN && c.env.TELEGRAM_BOT_TOKEN.trim().length > 0),
      r2Configured: storage.hasR2(),
    },
  });
});

// GET /api/admin/emoji/packs/:id - Admin preview before publish
emojiAdminRoutes.get("/packs/:id", async (c) => {
  const db = c.env.DB;
  const id = c.req.param("id");
  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (!pack) {
    return c.json({ success: false, error: "Emoji pack not found" }, 404);
  }
  const storage = new AssetStorage(c.env);
  const { results } = await db
    .prepare("SELECT * FROM emoji_items WHERE pack_id = ? ORDER BY COALESCE(item_index, 0) ASC, created_at ASC, id ASC")
    .bind(id)
    .all<EmojiItemRow>();

  return c.json({
    success: true,
    data: {
      id: pack.id,
      name: pack.name,
      title: pack.title,
      packType: pack.pack_type || "emoji",
      description: pack.description || "",
      category: pack.category || "General",
      featured: Boolean(pack.featured),
      version: Number(pack.version || 1),
      accessTier: pack.access_tier || "free",
      rightsStatus: pack.rights_status || "approved",
      creatorAttribution: pack.creator_attribution || "",
      licenseAttribution: pack.license_attribution || "",
      licenseEvidence: pack.license_evidence || "",
      publicationStatus: pack.publication_status || "published",
      importStatus: pack.import_status || "complete",
      importExpectedCount: Number(pack.import_expected_count || 0),
      importFailedCount: Number(pack.import_failed_count || 0),
      importErrorSummary: pack.import_error_summary || "",
      source: pack.source,
      enabled: Boolean(pack.enabled),
      itemCount: (results || []).length,
      items: (results || []).map((r) => ({
        id: r.id,
        altEmoji: r.alt_emoji,
        kind: r.kind,
        fileFormat: r.file_format || (r.kind === "lottie" ? "lottie" : "webp"),
        byteSize: Number(r.byte_size || 0),
        sha256: r.sha256 || "",
        url: buildItemAssetUrl(storage, r),
        enabled: Boolean(r.enabled),
        externalReference: r.external_reference || "",
      })),
    },
  });
});

// POST /api/admin/emoji/packs - Create a new emoji/sticker pack
emojiAdminRoutes.post("/packs", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const name = String(body.name || "").trim().toLowerCase();
  const title = String(body.title || "").trim();
  const source = String(body.source || "upload").trim();
  const licenseAttribution = String(body.licenseAttribution || "").trim();
  const packType = String(body.packType || "emoji").trim().toLowerCase();
  const description = String(body.description || "").trim().slice(0, 500);
  const category = String(body.category || "General").trim().slice(0, 80) || "General";
  const featured = body.featured ? 1 : 0;
  const creatorAttribution = String(body.creatorAttribution || "").trim().slice(0, 200);
  const licenseEvidence = String(body.licenseEvidence || "").trim().slice(0, 500);
  const accessTier = String(body.accessTier || "free").trim().toLowerCase();
  const rightsStatus = String(body.rightsStatus || "approved").trim().toLowerCase();
  const publicationStatus = String(body.publicationStatus || "published").trim().toLowerCase();

  if (!name || !title || !/^[a-z0-9_-]{2,50}$/.test(name)) {
    return c.json({ success: false, error: "Invalid pack name or title. Name must be 2-50 alphanumeric chars." }, 400);
  }
  if (!["emoji", "sticker"].includes(packType)) {
    return c.json({ success: false, error: "packType must be emoji or sticker" }, 400);
  }
  if (!["free", "premium"].includes(accessTier)) {
    return c.json({ success: false, error: "accessTier must be free or premium" }, 400);
  }
  if (!["approved", "limited_external", "pending_review", "rejected"].includes(rightsStatus)) {
    return c.json({ success: false, error: "Invalid rightsStatus" }, 400);
  }
  if (!["draft", "published", "unpublished"].includes(publicationStatus)) {
    return c.json({ success: false, error: "Invalid publicationStatus" }, 400);
  }
  if (accessTier === "premium" && rightsStatus !== "approved") {
    return c.json({ success: false, error: "Only rights-approved packs may use EVE Premium gating." }, 403);
  }

  const existing = await db.prepare("SELECT id FROM emoji_packs WHERE name = ?").bind(name).first();
  if (existing) {
    return c.json({ success: false, error: `Emoji pack '${name}' already exists.` }, 409);
  }

  const id = `pack_${crypto.randomUUID()}`;
  const now = Date.now();

  const insertStmt = db
    .prepare(
      `INSERT INTO emoji_packs (
        id, name, title, source, license_attribution, enabled, created_at, created_by,
        pack_type, description, category, featured, version, creator_attribution,
        rights_status, license_evidence, publication_status, access_tier, updated_at
      ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`
    )
    .bind(
      id, name, title, source, licenseAttribution, now, user?.email || "admin",
      packType, description, category, featured, creatorAttribution,
      rightsStatus, licenseEvidence, publicationStatus, accessTier, now
    );

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CREATE_EMOJI_PACK",
    description: `Created ${packType} pack '${title}' (${name}) from source '${source}'`,
    targetType: "emoji_pack",
    targetId: id,
    metadata: { name, title, packType, source, accessTier, rightsStatus },
  });

  await db.batch([insertStmt, auditStmt]);

  return c.json({
    success: true,
    data: {
      id,
      name,
      title,
      packType,
      description,
      category,
      featured: Boolean(featured),
      version: 1,
      accessTier,
      rightsStatus,
      creatorAttribution,
      licenseAttribution,
      publicationStatus,
      source,
      enabled: true,
      itemCount: 0,
      createdAt: now,
      createdBy: user?.email || "admin",
    },
  }, 201);
});

// PUT /api/admin/emoji/packs/:id - Update pack metadata
emojiAdminRoutes.put("/packs/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (!pack) {
    return c.json({ success: false, error: "Emoji pack not found" }, 404);
  }

  const title = body.title !== undefined ? String(body.title).trim() : pack.title;
  const packType = body.packType !== undefined ? String(body.packType).trim().toLowerCase() : (pack.pack_type || "emoji");
  const description = body.description !== undefined ? String(body.description).trim().slice(0, 500) : (pack.description || "");
  const category = body.category !== undefined ? String(body.category).trim().slice(0, 80) : (pack.category || "General");
  const featured = body.featured !== undefined ? (body.featured ? 1 : 0) : (pack.featured || 0);
  const accessTier = body.accessTier !== undefined ? String(body.accessTier).trim().toLowerCase() : (pack.access_tier || "free");
  const rightsStatus = body.rightsStatus !== undefined ? String(body.rightsStatus).trim().toLowerCase() : (pack.rights_status || "approved");
  const publicationStatus = body.publicationStatus !== undefined ? String(body.publicationStatus).trim().toLowerCase() : (pack.publication_status || "published");
  const creatorAttribution = body.creatorAttribution !== undefined ? String(body.creatorAttribution).trim().slice(0, 200) : (pack.creator_attribution || "");
  const licenseAttribution = body.licenseAttribution !== undefined ? String(body.licenseAttribution).trim() : (pack.license_attribution || "");
  const licenseEvidence = body.licenseEvidence !== undefined ? String(body.licenseEvidence).trim().slice(0, 500) : (pack.license_evidence || "");

  if (!title || !["emoji", "sticker"].includes(packType) || !["free", "premium"].includes(accessTier) ||
      !["approved", "limited_external", "pending_review", "rejected"].includes(rightsStatus) ||
      !["draft", "published", "unpublished"].includes(publicationStatus)) {
    return c.json({ success: false, error: "Invalid pack update fields" }, 400);
  }
  if ((accessTier === "premium" || publicationStatus === "published") && rightsStatus !== "approved") {
    return c.json({ success: false, error: "Packs without redistribution approval cannot be published or monetized." }, 403);
  }

  const now = Date.now();
  const nextVersion = Number(pack.version || 1) + 1;

  const updateStmt = db
    .prepare(
      `UPDATE emoji_packs
       SET title = ?, pack_type = ?, description = ?, category = ?, featured = ?,
           access_tier = ?, rights_status = ?, publication_status = ?,
           creator_attribution = ?, license_attribution = ?, license_evidence = ?,
           version = ?, updated_at = ?
       WHERE id = ?`
    )
    .bind(
      title, packType, description, category, featured,
      accessTier, rightsStatus, publicationStatus,
      creatorAttribution, licenseAttribution, licenseEvidence,
      nextVersion, now, id
    );

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "UPDATE_EMOJI_PACK",
    description: `Updated ${packType} pack '${title}' to v${nextVersion}`,
    targetType: "emoji_pack",
    targetId: id,
    metadata: { title, packType, accessTier, rightsStatus, publicationStatus, version: nextVersion },
  });

  await db.batch([updateStmt, auditStmt]);

  return c.json({
    success: true,
    data: { id, title, packType, accessTier, rightsStatus, publicationStatus, version: nextVersion, updatedAt: now },
  });
});

// POST /api/admin/emoji/packs/:id/publish - Explicitly publish/unpublish approved pack
emojiAdminRoutes.post("/packs/:id/publish", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));
  const publicationStatus = String(body.publicationStatus || "published").trim().toLowerCase();

  if (!["draft", "published", "unpublished"].includes(publicationStatus)) {
    return c.json({ success: false, error: "publicationStatus must be draft, published, or unpublished" }, 400);
  }

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (!pack) {
    return c.json({ success: false, error: "Emoji pack not found" }, 404);
  }
  if (publicationStatus === "published") {
    if ((pack.rights_status || "approved") !== "approved") {
      return c.json({ success: false, error: "Only rights-approved packs can be published." }, 403);
    }
    const countRow = await db.prepare("SELECT COUNT(*) AS n FROM emoji_items WHERE pack_id = ? AND enabled = 1").bind(id).first<{ n: number }>();
    if (!countRow || countRow.n <= 0) {
      return c.json({ success: false, error: "Cannot publish an empty pack without enabled items." }, 400);
    }
  }

  const now = Date.now();
  const nextVersion = Number(pack.version || 1) + 1;
  const stmt = db
    .prepare("UPDATE emoji_packs SET publication_status = ?, enabled = ?, version = ?, updated_at = ? WHERE id = ?")
    .bind(publicationStatus, publicationStatus === "unpublished" ? 0 : 1, nextVersion, now, id);
  const { stmt: audit } = prepareAdminAuditLog(db, user, {
    actionType: "PUBLISH_EMOJI_PACK",
    description: `Set pack '${pack.title}' publication to ${publicationStatus}`,
    targetType: "emoji_pack",
    targetId: id,
  });
  await db.batch([stmt, audit]);

  return c.json({ success: true, data: { id, publicationStatus, version: nextVersion } });
});

// PUT /api/admin/emoji/packs/:id/status - Toggle pack enabled/disabled (per-pack kill switch)
emojiAdminRoutes.put("/packs/:id/status", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (!pack) {
    return c.json({ success: false, error: "Emoji pack not found" }, 404);
  }

  const enabled = body.enabled === false ? 0 : 1;

  const updateStmt = db.prepare("UPDATE emoji_packs SET enabled = ?, updated_at = ? WHERE id = ?").bind(enabled, Date.now(), id);
  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "TOGGLE_EMOJI_PACK_STATUS",
    description: `${enabled ? "Enabled" : "Disabled"} emoji pack '${pack.title}' (${pack.name})`,
    targetType: "emoji_pack",
    targetId: id,
    metadata: { enabled: Boolean(enabled), name: pack.name },
  });

  await db.batch([updateStmt, auditStmt]);

  return c.json({ success: true, data: { id, enabled: Boolean(enabled) } });
});

// PUT /api/admin/emoji/global-telegram-status - Toggle global Telegram emoji kill switch
emojiAdminRoutes.put("/global-telegram-status", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const enabled = body.enabled === false ? false : true;
  const now = Date.now();
  const configJson = JSON.stringify({ enabled });

  const stmt = db
    .prepare(
      "INSERT INTO app_content (id, title, body, updated_at) VALUES ('telegram_emoji_global', 'Telegram Emoji Global Switch', ?, ?) ON CONFLICT(id) DO UPDATE SET body = excluded.body, updated_at = excluded.updated_at"
    )
    .bind(configJson, now);

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "TOGGLE_GLOBAL_TELEGRAM_EMOJI",
    description: `${enabled ? "Enabled" : "Disabled"} global Telegram emoji switch`,
    targetType: "global_setting",
    targetId: "telegram_emoji_global",
    metadata: { enabled },
  });

  await db.batch([stmt, auditStmt]);

  return c.json({ success: true, data: { globalTelegramEnabled: enabled } });
});

// DELETE /api/admin/emoji/packs/:id - Permanently delete an emoji/sticker pack (Takedown)
emojiAdminRoutes.delete("/packs/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;

  const pack = await db.prepare("SELECT * FROM emoji_packs WHERE id = ?").bind(id).first<EmojiPackRow>();
  if (!pack) {
    return c.json({ success: false, error: "Emoji pack not found" }, 404);
  }

  const snapshotQuery =
    "SELECT json_group_array(json_object('id', id, 'key', storage_key)) AS snapshot FROM (SELECT id, storage_key FROM emoji_items WHERE pack_id = ? ORDER BY id)";
  const snapshot = (await db.prepare(snapshotQuery).bind(id).first<{ snapshot: string }>())?.snapshot || "[]";
  const items = JSON.parse(snapshot) as { id: string; key: string }[];
  const cleanupPaths = items.map((item) => item.key);
  if (pack.thumbnail_key) cleanupPaths.push(pack.thumbnail_key);

  const { id: auditId, stmt: audit } = prepareAdminAuditLog(db, user, {
    actionType: "DELETE_EMOJI_PACK",
    description: `Removed emoji pack '${pack.title}' (${pack.name})`,
    targetType: "emoji_pack",
    targetId: id,
    metadata: { deletedItemsCount: items.length },
    condition: {
      sql: `EXISTS (SELECT 1 FROM emoji_packs WHERE id = ? AND created_at = ?) AND (${snapshotQuery.replace(" AS snapshot", "")}) = ?`,
      bindings: [id, pack.created_at, id, snapshot],
    },
  });
  const gate = "EXISTS (SELECT 1 FROM admin_audit_log WHERE id = ?)";
  const result = await db.batch([
    audit,
    db.prepare(`DELETE FROM emoji_placements WHERE item_id IN (SELECT id FROM emoji_items WHERE pack_id = ?) AND ${gate}`).bind(id, auditId),
    db.prepare(`DELETE FROM emoji_items WHERE pack_id = ? AND ${gate}`).bind(id, auditId),
    db.prepare(`DELETE FROM emoji_packs WHERE id = ? AND ${gate}`).bind(id, auditId),
  ]);
  if (!result[3]?.meta?.changes) {
    return c.json({ success: false, code: "EMOJI_CHANGED", error: "This pack changed. Refresh before deleting it." }, 409);
  }
  const mediaCleanupComplete = await cleanupEmojiMedia(c.env, cleanupPaths, c.res.headers.get("X-Request-ID"));
  return c.json({
    success: true,
    data: { mediaCleanupComplete },
    message: "Emoji pack removed.",
    ...(mediaCleanupComplete ? {} : { warning: "Pack removed. Some media cleanup could not be completed." }),
  });
});

// GET /api/admin/emoji/items - List emoji/sticker items with pagination and filters
emojiAdminRoutes.get("/items", async (c) => {
  const db = c.env.DB;
  const packId = c.req.query("packId")?.trim();
  const q = c.req.query("q")?.trim();
  const offset = Number(c.req.query("offset") ?? "0");
  const limit = Number(c.req.query("limit") ?? "50");
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    return c.json({ success: false, error: "offset must be a non-negative integer and limit must be 1-100." }, 400);
  }

  let query = "SELECT i.*, p.title as pack_title, p.name as pack_slug FROM emoji_items i JOIN emoji_packs p ON i.pack_id = p.id WHERE 1=1";
  const params: any[] = [];

  if (packId) {
    query += " AND i.pack_id = ?";
    params.push(packId);
  }
  if (q) {
    query += " AND (i.alt_emoji LIKE ? OR i.source LIKE ? OR i.pack_name LIKE ?)";
    const wildcard = `%${q}%`;
    params.push(wildcard, wildcard, wildcard);
  }

  query += " ORDER BY i.created_at DESC, i.id DESC LIMIT ? OFFSET ?";
  params.push(limit, offset);

  const { results } = await db.prepare(query).bind(...params).all<any>();
  const storage = new AssetStorage(c.env);

  const list = (results || []).map((r) => ({
    id: r.id,
    packId: r.pack_id,
    packTitle: r.pack_title,
    packSlug: r.pack_slug,
    altEmoji: r.alt_emoji,
    kind: r.kind,
    fileFormat: r.file_format || (r.kind === "lottie" ? "lottie" : "webp"),
    url: buildItemAssetUrl(storage, r),
    storageKey: r.storage_key,
    byteSize: r.byte_size,
    sha256: r.sha256,
    enabled: Boolean(r.enabled),
    premiumOrigin: Boolean(r.premium_origin),
    telegramCustomEmojiId: r.telegram_custom_emoji_id,
    source: r.source,
    createdAt: r.created_at,
  }));

  return c.json({ success: true, data: list, offset, limit });
});

// DELETE /api/admin/emoji/items/:id - Delete an individual emoji/sticker item
emojiAdminRoutes.delete("/items/:id", async (c) => {
  const user = c.get("user");
  const id = c.req.param("id");
  const db = c.env.DB;

  const item = await db.prepare("SELECT * FROM emoji_items WHERE id = ?").bind(id).first<EmojiItemRow>();
  if (!item) {
    return c.json({ success: false, error: "Emoji item not found" }, 404);
  }

  const { id: auditId, stmt: audit } = prepareAdminAuditLog(db, user, {
    actionType: "DELETE_EMOJI_ITEM",
    description: `Removed emoji item ${item.alt_emoji}`,
    targetType: "emoji_item",
    targetId: id,
    condition: {
      sql: "EXISTS (SELECT 1 FROM emoji_items WHERE id = ? AND storage_key = ? AND created_at = ?)",
      bindings: [id, item.storage_key, item.created_at],
    },
  });
  const gate = "EXISTS (SELECT 1 FROM admin_audit_log WHERE id = ?)";
  const result = await db.batch([
    audit,
    db.prepare(`DELETE FROM emoji_placements WHERE item_id = ? AND ${gate}`).bind(id, auditId),
    db.prepare(`DELETE FROM emoji_items WHERE id = ? AND ${gate}`).bind(id, auditId),
  ]);
  if (!result[2]?.meta?.changes) {
    return c.json({ success: false, code: "EMOJI_CHANGED", error: "This item changed. Refresh before deleting it." }, 409);
  }
  const mediaCleanupComplete = await cleanupEmojiMedia(c.env, [item.storage_key], c.res.headers.get("X-Request-ID"));
  return c.json({
    success: true,
    data: { mediaCleanupComplete },
    message: "Emoji item removed.",
    ...(mediaCleanupComplete ? {} : { warning: "Item removed. Some media cleanup could not be completed." }),
  });
});

// POST /api/admin/emoji/upload - Upload custom Lottie JSON, TGS, WebM video, or static WebP/PNG asset
emojiAdminRoutes.post("/upload", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const contentType = (c.req.header("content-type") || "").toLowerCase();

  let packId = c.req.query("packId") || "";
  let altEmoji = c.req.query("altEmoji") || "✨";
  const itemIndex = Number(c.req.query("itemIndex") ?? "0");
  let rawBytes: Uint8Array;
  let kind: "lottie" | "static" | "video" = "lottie";
  let fileFormat = "lottie";
  let mime = "application/json";

  try {
    const reader = c.req.raw.body?.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    if (reader) {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.length;
        if (total > 1024 * 1024) {
          await reader.cancel();
          return c.json({ success: false, error: "Upload request exceeds 1MB." }, 413);
        }
        chunks.push(value);
      }
    }
    const bytes = new Uint8Array(total);
    let position = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, position);
      position += chunk.length;
    }
    if (!total) return c.json({ success: false, error: "Empty upload body" }, 400);

    if (contentType.includes("image/webp") || contentType.includes("image/png")) {
      kind = "static";
      const png = bytes.length >= 24 && [137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => bytes[i] === v);
      const webp = bytes.length >= 16 && new TextDecoder().decode(bytes.slice(0, 4)) === "RIFF" && new TextDecoder().decode(bytes.slice(8, 12)) === "WEBP";
      if ((!png && !webp) || (png && !contentType.includes("image/png")) || (webp && !contentType.includes("image/webp"))) {
        return c.json({ success: false, error: "Image bytes do not match the PNG/WebP content type." }, 400);
      }
      mime = png ? "image/png" : "image/webp";
      fileFormat = png ? "png" : "webp";
      rawBytes = bytes;
    } else if (contentType.includes("video/webm")) {
      validateWebmBytes(bytes);
      kind = "video";
      fileFormat = "webm";
      mime = "video/webm";
      rawBytes = bytes;
    } else if (contentType.includes("application/x-tgsticker") || contentType.includes("application/gzip")) {
      const jsonStr = await decompressGzipWithGuard(bytes, MAX_ANIMATION_SIZE_BYTES);
      validateLottieJson(jsonStr);
      rawBytes = new TextEncoder().encode(jsonStr);
      kind = "lottie";
      fileFormat = "lottie";
      mime = "application/json";
    } else {
      const parsed = JSON.parse(new TextDecoder().decode(bytes));
      const animation = parsed?.animationData ?? parsed;
      const str = typeof animation === "string" ? animation : JSON.stringify(animation);
      validateLottieJson(str);
      rawBytes = new TextEncoder().encode(str);
      if (parsed.packId !== undefined) {
        if (typeof parsed.packId !== "string") throw new Error("packId must be text.");
        packId = parsed.packId;
      }
      if (parsed.altEmoji !== undefined) {
        if (typeof parsed.altEmoji !== "string") throw new Error("altEmoji must be text.");
        altEmoji = parsed.altEmoji;
      }
    }
  } catch (err) {
    return c.json({ success: false, error: err instanceof SyntaxError ? "Invalid JSON animation." : (err as Error).message }, 400);
  }

  const maxCap = kind === "video" ? MAX_VIDEO_STICKER_BYTES : MAX_ANIMATION_SIZE_BYTES;
  if (!rawBytes.length || rawBytes.length > maxCap) {
    return c.json({ success: false, error: `Asset must be between 1 byte and ${Math.round(maxCap / 1024)}KB.` }, 413);
  }
  if (!altEmoji.trim() || altEmoji.length > 100) return c.json({ success: false, error: "altEmoji must contain 1-100 characters." }, 400);

  let targetPackTitle = "Custom Uploads";
  if (packId) {
    const existingPack = await db.prepare("SELECT id, title FROM emoji_packs WHERE id = ?").bind(packId).first<{ id: string; title: string }>();
    if (!existingPack) {
      return c.json({ success: false, error: "Emoji pack not found." }, 404);
    }
    targetPackTitle = existingPack.title;
  } else {
    let customPack = await db.prepare("SELECT id, title FROM emoji_packs WHERE source = 'upload' LIMIT 1").first<EmojiPackRow>();
    if (!customPack) {
      const newPackId = "pack_custom_uploads";
      const packInsert = db
        .prepare(
          "INSERT OR IGNORE INTO emoji_packs (id, name, title, source, license_attribution, enabled, created_at, created_by) VALUES (?, 'custom_uploads', 'Custom Uploads', 'upload', 'Admin user uploaded assets', 1, ?, ?)"
        )
        .bind(newPackId, Date.now(), user?.email || "admin");
      const { stmt: packAudit } = prepareAdminAuditLog(db, user, {
        actionType: "CREATE_EMOJI_PACK",
        description: "Created custom uploads pack",
        targetType: "emoji_pack",
        targetId: newPackId,
        condition: { sql: "changes() = 1", bindings: [] },
      });
      await db.batch([packInsert, packAudit]);
      packId = newPackId;
    } else {
      packId = customPack.id;
      targetPackTitle = customPack.title;
    }
  }

  const hash = await sha256Hex(rawBytes);
  const rawStorageKey = `emoji/uploads/${crypto.randomUUID()}/${hash}.${fileFormat === "lottie" ? "json" : fileFormat}`;

  const storage = new AssetStorage(c.env);
  const storedRef = await storage.putObject(rawStorageKey, rawBytes, mime);
  const storageKey = storedRef.startsWith("tg:") ? storedRef : rawStorageKey;

  const id = `emoji_${crypto.randomUUID()}`;
  const now = Date.now();
  const publicUrl = buildItemAssetUrl(storage, { id, storage_key: storageKey });

  const insertStmt = db
    .prepare(
      `INSERT INTO emoji_items 
      (id, pack_id, alt_emoji, kind, storage_key, byte_size, sha256, enabled, premium_origin, source, pack_name, uploader, created_at, item_index, file_format, mime_type, is_animated, is_video, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, 'upload', ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .bind(
      id, packId, altEmoji, kind, storageKey, rawBytes.length, hash,
      targetPackTitle, user?.email || "admin", now,
      Number.isSafeInteger(itemIndex) ? itemIndex : 0,
      fileFormat, mime, kind === "static" ? 0 : 1, kind === "video" ? 1 : 0, now
    );

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "UPLOAD_EMOJI",
    description: `Uploaded ${kind} asset ${altEmoji} (${rawBytes.length} bytes)`,
    targetType: "emoji_item",
    targetId: id,
    metadata: { altEmoji, kind, fileFormat, storageKey, byteSize: rawBytes.length, sha256: hash },
  });

  try {
    await db.batch([
      insertStmt,
      db.prepare("UPDATE emoji_packs SET version = COALESCE(version, 1) + 1, updated_at = ? WHERE id = ?").bind(now, packId),
      auditStmt,
    ]);
  } catch (error) {
    await cleanupEmojiMedia(c.env, [storageKey], c.res.headers.get("X-Request-ID"));
    throw error;
  }

  return c.json(
    {
      success: true,
      data: {
        id,
        packId,
        altEmoji,
        kind,
        fileFormat,
        mimeType: mime,
        url: publicUrl,
        storageKey,
        byteSize: rawBytes.length,
        sha256: hash,
        enabled: true,
      },
    },
    201
  );
});

// POST /api/admin/emoji/import/telegram - Rights-gated import via official Telegram Bot API (server-side token only)
emojiAdminRoutes.post("/import/telegram", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const botToken = c.env.TELEGRAM_BOT_TOKEN?.trim();

  if (!botToken) {
    return c.json(
      {
        success: false,
        error: "Telegram import is not configured. TELEGRAM_BOT_TOKEN is not set on the Cloudflare Worker.",
      },
      400
    );
  }

  const body = await c.req.json().catch(() => ({}));
  let packName = String(body.packName || body.name || "").trim();
  const customEmojiIds: string[] = Array.isArray(body.customEmojiIds) ? body.customEmojiIds : [];
  const offset = Number(body.offset ?? 0);
  const limit = Number(body.limit ?? 5);
  const importAll = body.importAll === true;
  const rightsBasis = String(body.rightsBasis || "").trim().toLowerCase();
  const licenseEvidence = String(body.licenseEvidence || "").trim().slice(0, 500);
  const requestedAccessTier = String(body.accessTier || "free").trim().toLowerCase();
  const category = String(body.category || "General").trim().slice(0, 80) || "General";

  if (
    !Number.isSafeInteger(offset) ||
    offset < 0 ||
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > 10 ||
    customEmojiIds.length > 100 ||
    customEmojiIds.some((id) => typeof id !== "string" || !/^\d{1,30}$/.test(id))
  ) {
    return c.json({ success: false, error: "Provide valid pagination and up to 100 numeric custom emoji IDs." }, 400);
  }

  if (/add(?:stickers|emoji)\//.test(packName)) {
    packName = packName.split(/add(?:stickers|emoji)\//)[1].split(/[?#/]/)[0].trim();
  }
  if (packName && !/^[a-zA-Z0-9_]{1,100}$/.test(packName)) {
    return c.json({ success: false, error: "Provide a valid Telegram sticker pack name or link." }, 400);
  }

  if (!packName && customEmojiIds.length === 0) {
    return c.json({ success: false, error: "Must specify a Telegram pack name or customEmojiIds list" }, 400);
  }

  let stickers: any[] = [];
  let packTitle = packName || "Telegram Custom Emoji";
  let detectedPackType: "emoji" | "sticker" = customEmojiIds.length > 0 ? "emoji" : "sticker";
  let setThumbnailFileId = "";

  try {
    if (packName) {
      const getSetUrl = `https://api.telegram.org/bot${botToken}/getStickerSet?name=${encodeURIComponent(packName)}`;
      const setRes = await fetch(getSetUrl);
      if (!setRes.ok) {
        return c.json({ success: false, error: "Telegram could not load this sticker pack. Check the name and try again." }, 502);
      }
      const setData: any = await setRes.json();
      if (!setData.ok || !setData.result) {
        return c.json({ success: false, error: `Telegram returned: ${setData.description || "Sticker pack not found"}` }, 404);
      }
      stickers = setData.result.stickers || [];
      packTitle = setData.result.title || packName;
      if (setData.result.sticker_type === "custom_emoji") {
        detectedPackType = "emoji";
      } else {
        detectedPackType = "sticker";
      }
      setThumbnailFileId = String(setData.result.thumbnail?.file_id || "");
    } else if (customEmojiIds.length > 0) {
      const getStickersUrl = `https://api.telegram.org/bot${botToken}/getCustomEmojiStickers?custom_emoji_ids=${encodeURIComponent(
        JSON.stringify(customEmojiIds.slice(offset, offset + limit))
      )}`;
      const res = await fetch(getStickersUrl);
      if (!res.ok) {
        return c.json({ success: false, error: "Failed to fetch custom emoji stickers from Telegram Bot API" }, 400);
      }
      const data: any = await res.json();
      stickers = data.result || [];
      detectedPackType = "emoji";
    }
  } catch (err: any) {
    return c.json({ success: false, error: "Unable to connect to Telegram. Please retry." }, 502);
  }

  const packTypeOverride = String(body.packType || "").trim().toLowerCase();
  const finalPackType: "emoji" | "sticker" =
    packTypeOverride === "emoji" || packTypeOverride === "sticker" ? packTypeOverride : detectedPackType;

  const packSlug = (packName || `tg_${customEmojiIds[0] || "custom"}`).toLowerCase().replace(/[^a-z0-9_-]/g, "_");
  const isLimitedRights = LIMITED_RIGHTS_BASES.has(rightsBasis);
  const rightsStatus = isLimitedRights
    ? "limited_external"
    : rightsBasis && !VALID_APPROVED_RIGHTS_BASES.has(rightsBasis)
    ? "pending_review"
    : "approved";

  let packRow = await db.prepare("SELECT * FROM emoji_packs WHERE name = ?").bind(packSlug).first<EmojiPackRow>();
  let packId: string;
  const now = Date.now();
  const attribution =
    licenseEvidence ||
    (rightsBasis ? `Rights basis: ${rightsBasis}` : "Telegram Official Sticker/Custom Emoji Pack. Admin-authorized import.");

  if (!packRow) {
    packId = `pack_tg_${packSlug}`;
    const packInsert = db
      .prepare(
        `INSERT INTO emoji_packs (
          id, name, title, source, license_attribution, enabled, created_at, created_by,
          pack_type, category, rights_status, license_evidence, publication_status, access_tier, updated_at
        ) VALUES (?, ?, ?, 'telegram', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        packId,
        packSlug,
        packTitle,
        attribution,
        isLimitedRights ? 0 : 1,
        now,
        user?.email || "admin",
        finalPackType,
        category,
        rightsStatus,
        licenseEvidence,
        isLimitedRights ? "unpublished" : "published",
        requestedAccessTier === "premium" && rightsStatus === "approved" ? "premium" : "free",
        now
      );
    const { stmt: packAudit } = prepareAdminAuditLog(db, user, {
      actionType: "CREATE_EMOJI_PACK",
      description: `Created imported ${finalPackType} pack '${packTitle}'`,
      targetType: "emoji_pack",
      targetId: packId,
      metadata: { rightsStatus, rightsBasis },
    });
    await db.batch([packInsert, packAudit]);
  } else {
    packId = packRow.id;
  }

  // Strict copyright / content-rights boundary: never copy/mirror assets without redistribution rights.
  if (isLimitedRights || rightsStatus === "pending_review") {
    const extRef = packName
      ? (finalPackType === "emoji" ? `https://t.me/addemoji/${packName}` : `https://t.me/addstickers/${packName}`)
      : "";
    await db
      .prepare(
        `UPDATE emoji_packs SET rights_status = ?, license_evidence = ?, import_status = ?, import_expected_count = ?, import_error_summary = ?, publication_status = 'unpublished', enabled = 0, updated_at = ? WHERE id = ?`
      )
      .bind(
        rightsStatus,
        licenseEvidence,
        "limited_external",
        stickers.length,
        "Redistribution rights not verified; asset mirroring blocked.",
        now,
        packId
      )
      .run();
    return c.json({
      success: true,
      data: {
        packId,
        packName,
        packTitle,
        packType: finalPackType,
        totalStickers: stickers.length,
        importedCount: 0,
        failedCount: 0,
        redistributionBlocked: true,
        rightsStatus,
        externalReference: extRef,
        importStatus: "limited_external",
      },
    });
  }

  const batchSlice = packName ? (importAll ? stickers : stickers.slice(offset, offset + limit)) : stickers;
  const storage = new AssetStorage(c.env);
  let importedCount = 0;
  const errors: string[] = [];

  let packThumbnailKey = packRow?.thumbnail_key || "";
  if (!packThumbnailKey && setThumbnailFileId) {
    try {
      const thumbMetaRes = await fetch(`https://api.telegram.org/bot${botToken}/getFile?file_id=${setThumbnailFileId}`);
      if (thumbMetaRes.ok) {
        const thumbMeta: any = await thumbMetaRes.json();
        const thumbPath = thumbMeta?.result?.file_path;
        if (thumbPath) {
          const thumbDownload = await fetch(`https://api.telegram.org/file/bot${botToken}/${thumbPath}`);
          if (thumbDownload.ok) {
            const thumbBytes = new Uint8Array(await thumbDownload.arrayBuffer());
            const thumbExt = thumbPath.endsWith(".tgs") ? "tgs" : thumbPath.endsWith(".webm") ? "webm" : "webp";
            const thumbMime = thumbExt === "webp" ? "image/webp" : thumbExt === "webm" ? "video/webm" : "application/x-tgsticker";
            const thumbHash = await sha256Hex(thumbBytes);
            const rawThumbKey = `emoji/telegram/${packSlug}/thumb_${thumbHash.slice(0, 12)}.${thumbExt}`;
            const storedThumbRef = await storage.putObject(rawThumbKey, thumbBytes, thumbMime);
            packThumbnailKey = storedThumbRef.startsWith("tg:") ? storedThumbRef : rawThumbKey;
          }
        }
      }
    } catch {}
  }

  for (let i = 0; i < batchSlice.length; i++) {
    const sticker = batchSlice[i];
    const itemIndex = importAll ? i : offset + i;
    try {
      const getFileUrl = `https://api.telegram.org/bot${botToken}/getFile?file_id=${sticker.file_id}`;
      const fileRes = await fetch(getFileUrl);
      if (!fileRes.ok) {
        errors.push(`Item #${itemIndex + 1} (${sticker.emoji || ""}): getFile failed (${fileRes.status}).`);
        continue;
      }
      const fileData: any = await fileRes.json();
      if (!fileData.ok || !fileData.result?.file_path) {
        errors.push(`Item #${itemIndex + 1}: Telegram file path unavailable.`);
        continue;
      }

      const filePath = String(fileData.result.file_path);
      const downloadUrl = `https://api.telegram.org/file/bot${botToken}/${filePath}`;
      const downRes = await fetch(downloadUrl);
      if (!downRes.ok) {
        errors.push(`Item #${itemIndex + 1}: download failed (${downRes.status}).`);
        continue;
      }

      const fileBytes = new Uint8Array(await downRes.arrayBuffer());
      let finalBytes: Uint8Array;
      let kind: "lottie" | "static" | "video" = "lottie";
      let fileFormat = "lottie";
      let mime = "application/json";

      if (filePath.endsWith(".tgs") || (sticker.is_animated && !sticker.is_video)) {
        const decompressedJson = await decompressGzipWithGuard(fileBytes, MAX_ANIMATION_SIZE_BYTES);
        validateLottieJson(decompressedJson);
        finalBytes = new TextEncoder().encode(decompressedJson);
        kind = "lottie";
        fileFormat = "lottie";
        mime = "application/json";
      } else if (filePath.endsWith(".webm") || sticker.is_video) {
        validateWebmBytes(fileBytes);
        finalBytes = fileBytes;
        kind = "video";
        fileFormat = "webm";
        mime = "video/webm";
      } else if (filePath.endsWith(".webp") || filePath.endsWith(".png")) {
        if (fileBytes.length > MAX_ANIMATION_SIZE_BYTES) {
          errors.push(`Item #${itemIndex + 1} (${sticker.emoji || ""}): Exceeds 300KB limit.`);
          continue;
        }
        finalBytes = fileBytes;
        kind = "static";
        fileFormat = filePath.endsWith(".png") ? "png" : "webp";
        mime = fileFormat === "png" ? "image/png" : "image/webp";
      } else {
        errors.push(`Item #${itemIndex + 1} (${sticker.emoji || ""}): Unsupported extension '${filePath}'`);
        continue;
      }

      const hash = await sha256Hex(finalBytes);
      const ext = fileFormat === "lottie" ? "json" : fileFormat;
      const rawStorageKey = `emoji/telegram/${packSlug}/${crypto.randomUUID()}/${hash}.${ext}`;

      const storedItemRef = await storage.putObject(rawStorageKey, finalBytes, mime);
      const storageKey = storedItemRef.startsWith("tg:") ? storedItemRef : rawStorageKey;

      const itemId = `emoji_tg_${hash.slice(0, 16)}`;
      const altEmoji = sticker.emoji || "✨";
      const isPremium = Boolean(sticker.custom_emoji_id);

      const itemStatement = db
        .prepare(
          `INSERT INTO emoji_items 
          (id, pack_id, alt_emoji, kind, storage_key, byte_size, sha256, enabled, premium_origin, telegram_custom_emoji_id, source, pack_name, uploader, created_at, item_index, file_format, mime_type, is_animated, is_video, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'telegram', ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET
            storage_key = excluded.storage_key,
            byte_size = excluded.byte_size,
            sha256 = excluded.sha256,
            file_format = excluded.file_format,
            mime_type = excluded.mime_type,
            enabled = 1,
            updated_at = excluded.updated_at`
        )
        .bind(
          itemId,
          packId,
          altEmoji,
          kind,
          storageKey,
          finalBytes.length,
          hash,
          isPremium ? 1 : 0,
          sticker.custom_emoji_id || null,
          packTitle,
          user?.email || "admin",
          Date.now(),
          itemIndex,
          fileFormat,
          mime,
          kind === "static" ? 0 : 1,
          kind === "video" ? 1 : 0,
          Date.now()
        );
      await saveImportedItem(db, c.env, user, itemId, storageKey, itemStatement);

      importedCount++;
    } catch (itemErr: any) {
      errors.push(`Item #${itemIndex + 1} (${sticker.emoji || ""}): ${itemErr?.message || "Import failed."}`);
    }
  }

  const totalStickers = packName ? stickers.length : customEmojiIds.length;
  const nextOffset = !importAll && offset + limit < totalStickers ? offset + limit : null;
  const failedCount = errors.length;
  const importStatus =
    importedCount === 0 && failedCount > 0 ? "failed" : failedCount > 0 ? "partial" : "complete";

  await db
    .prepare(
      `UPDATE emoji_packs
       SET pack_type = ?, title = ?, thumbnail_key = COALESCE(NULLIF(?, ''), thumbnail_key),
           import_status = ?, import_expected_count = ?, import_failed_count = ?, import_error_summary = ?,
           version = COALESCE(version, 1) + 1, updated_at = ?
       WHERE id = ?`
    )
    .bind(
      finalPackType,
      packTitle,
      packThumbnailKey,
      importStatus,
      totalStickers,
      failedCount,
      errors.slice(0, 5).join(" | "),
      Date.now(),
      packId
    )
    .run();

  await recordAdminAction(db, user, {
    actionType: "IMPORT_TELEGRAM_EMOJI",
    description: `Imported ${importedCount}/${batchSlice.length} items into ${finalPackType} pack '${packTitle}'`,
    targetType: "emoji_pack",
    targetId: packId,
    metadata: { packName, packTitle, packType: finalPackType, importedCount, failedCount, offset, limit, totalStickers },
  });

  return c.json({
    success: true,
    data: {
      packId,
      packName,
      packTitle,
      packType: finalPackType,
      totalStickers,
      offset,
      limit,
      importedCount,
      failedCount,
      importStatus,
      nextOffset,
      errors: errors.length > 0 ? errors : undefined,
    },
  });
});

// POST /api/admin/emoji/import/noto - Import Google Noto animated emoji (Verified CDN)
emojiAdminRoutes.post("/import/noto", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const NOTO_PRESETS: Record<string, { codepoint: string; alt: string; name: string }[]> = {
    popular: [
      { codepoint: "1f600", alt: "😀", name: "Grinning Face" },
      { codepoint: "1f602", alt: "😂", name: "Tears of Joy" },
      { codepoint: "1f525", alt: "🔥", name: "Fire" },
      { codepoint: "2b50", alt: "⭐", name: "Star" },
      { codepoint: "1f389", alt: "🎉", name: "Party Popper" },
      { codepoint: "2764_fe0f", alt: "❤️", name: "Red Heart" },
      { codepoint: "1f44d", alt: "👍", name: "Thumbs Up" },
      { codepoint: "1f4af", alt: "💯", name: "Hundred Points" },
      { codepoint: "1f4a1", alt: "💡", name: "Light Bulb" },
      { codepoint: "1f3c6", alt: "🏆", name: "Trophy" },
    ],
    study: [
      { codepoint: "1f4d6", alt: "📖", name: "Open Book" },
      { codepoint: "270f_fe0f", alt: "✏️", name: "Pencil" },
      { codepoint: "1f393", alt: "🎓", name: "Graduation Cap" },
      { codepoint: "23f0", alt: "⏰", name: "Alarm Clock" },
      { codepoint: "1f4ca", alt: "📊", name: "Bar Chart" },
      { codepoint: "2705", alt: "✅", name: "Check Mark" },
    ],
  };

  const category = String(body.category || "popular").toLowerCase();
  const itemsToImport =
    Array.isArray(body.items) && body.items.length > 0 ? body.items : NOTO_PRESETS[category] || NOTO_PRESETS.popular;

  if (
    itemsToImport.length > 20 ||
    itemsToImport.some(
      (item: any) =>
        !item ||
        typeof item.codepoint !== "string" ||
        !/^[0-9a-f]{2,6}(?:_[0-9a-f]{2,6})*$/i.test(item.codepoint) ||
        (item.alt !== undefined && (typeof item.alt !== "string" || item.alt.length > 100))
    )
  ) {
    return c.json({ success: false, error: "Provide up to 20 valid Unicode codepoints and text alternatives." }, 400);
  }
  const packId = "pack_noto";
  if (!(await db.prepare("SELECT id FROM emoji_packs WHERE id = ?").bind(packId).first())) {
    return c.json({ success: false, error: "The Noto pack was removed. Create or restore its pack before importing." }, 409);
  }
  const storage = new AssetStorage(c.env);
  let importedCount = 0;
  const errors: string[] = [];

  for (let i = 0; i < itemsToImport.length; i++) {
    const item = itemsToImport[i];
    const codepoint = String(item.codepoint || "").toLowerCase().trim();
    const alt = item.alt || "✨";
    if (!codepoint) continue;

    const cdnUrl = `https://fonts.gstatic.com/s/e/notoemoji/latest/${codepoint}/lottie.json`;
    try {
      const res = await fetch(cdnUrl);
      if (!res.ok) {
        errors.push(`Failed to download Noto emoji ${codepoint} (${res.status})`);
        continue;
      }
      const jsonStr = await res.text();
      validateLottieJson(jsonStr);
      const rawBytes = new TextEncoder().encode(jsonStr);

      if (rawBytes.length > MAX_ANIMATION_SIZE_BYTES) {
        errors.push(`Noto emoji ${codepoint} exceeds 300KB limit.`);
        continue;
      }

      const hash = await sha256Hex(rawBytes);
      const rawStorageKey = `emoji/noto/${crypto.randomUUID()}/${hash}.json`;

      const storedNotoRef = await storage.putObject(rawStorageKey, rawBytes, "application/json");
      const storageKey = storedNotoRef.startsWith("tg:") ? storedNotoRef : rawStorageKey;

      const itemId = `emoji_noto_${codepoint}`;
      const itemStatement = db
        .prepare(
          `INSERT INTO emoji_items 
          (id, pack_id, alt_emoji, kind, storage_key, byte_size, sha256, enabled, premium_origin, source, pack_name, uploader, created_at, item_index, file_format, mime_type, is_animated, is_video, updated_at)
          VALUES (?, ?, ?, 'lottie', ?, ?, ?, 1, 0, 'noto', 'Noto Animated Emoji', ?, ?, ?, 'lottie', 'application/json', 1, 0, ?)
          ON CONFLICT(id) DO UPDATE SET storage_key = excluded.storage_key, enabled = 1, updated_at = excluded.updated_at`
        )
        .bind(itemId, packId, alt, storageKey, rawBytes.length, hash, user?.email || "admin", Date.now(), i, Date.now());
      await saveImportedItem(db, c.env, user, itemId, storageKey, itemStatement);

      importedCount++;
    } catch (err: any) {
      errors.push(`Error importing Noto ${codepoint}. Please retry.`);
    }
  }

  await recordAdminAction(db, user, {
    actionType: "IMPORT_NOTO_EMOJI",
    description: `Imported ${importedCount} Google Noto animated emoji items (${category})`,
    targetType: "emoji_pack",
    targetId: packId,
    metadata: { category, importedCount },
  });

  return c.json({
    success: true,
    data: {
      packId,
      importedCount,
      errors: errors.length > 0 ? errors : undefined,
    },
  });
});

// GET /api/admin/emoji/placements - List all surface placements
emojiAdminRoutes.get("/placements", async (c) => {
  const db = c.env.DB;
  const storage = new AssetStorage(c.env);

  const query = `
    SELECT 
      ep.*,
      ei.alt_emoji, ei.kind, ei.storage_key, ei.source as item_source,
      p.title as pack_title
    FROM emoji_placements ep
    JOIN emoji_items ei ON ep.item_id = ei.id
    JOIN emoji_packs p ON ei.pack_id = p.id
    ORDER BY ep.updated_at DESC
  `;
  const { results } = await db.prepare(query).all<any>();

  const list = (results || []).map((r) => ({
    id: r.id,
    slotType: r.slot_type,
    targetId: r.target_id,
    itemId: r.item_id,
    altEmoji: r.alt_emoji,
    kind: r.kind,
    url: buildItemAssetUrl(storage, { id: r.item_id, storage_key: r.storage_key }),
    packTitle: r.pack_title,
    updatedBy: r.updated_by || "",
    updatedAt: r.updated_at,
  }));

  return c.json({ success: true, data: list });
});

// POST /api/admin/emoji/placements - Set placement for exam card, category header, or broadcast
emojiAdminRoutes.post("/placements", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const body = await c.req.json().catch(() => ({}));

  const slotType = String(body.slotType || "").trim();
  const targetId = String(body.targetId || "").trim();
  const itemId = String(body.itemId || "").trim();

  if (!["exam_card", "category_header", "broadcast"].includes(slotType)) {
    return c.json({ success: false, error: "slotType must be one of: exam_card, category_header, broadcast" }, 400);
  }
  if (!targetId || !itemId || targetId.length > 128 || itemId.length > 128) {
    return c.json(
      { success: false, code: "INVALID_EMOJI_PLACEMENT", error: "targetId and itemId must each contain 1-128 characters." },
      400
    );
  }

  const targetQuery =
    slotType === "exam_card"
      ? "SELECT id FROM exams WHERE id = ?"
      : slotType === "broadcast"
      ? "SELECT id FROM notifications WHERE id = ?"
      : "SELECT id FROM exams WHERE category = ? LIMIT 1";
  if (!(await db.prepare(targetQuery).bind(targetId).first())) {
    return c.json(
      {
        success: false,
        code: "PLACEMENT_TARGET_NOT_FOUND",
        error: "Placement target not found. Refresh and check its ID or category name.",
      },
      404
    );
  }

  const item = await db.prepare("SELECT * FROM emoji_items WHERE id = ?").bind(itemId).first<EmojiItemRow>();
  if (!item) {
    return c.json({ success: false, error: `Emoji item '${itemId}' not found` }, 404);
  }

  const id = `plc_${crypto.randomUUID()}`;
  const now = Date.now();

  const insertStmt = db
    .prepare(
      `INSERT INTO emoji_placements (id, slot_type, target_id, item_id, updated_by, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(slot_type, target_id) DO UPDATE SET
        item_id = excluded.item_id,
        updated_by = excluded.updated_by,
        updated_at = excluded.updated_at`
    )
    .bind(id, slotType, targetId, itemId, user?.email || "admin", now);

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "SET_EMOJI_PLACEMENT",
    description: `Placed emoji ${item.alt_emoji} on ${slotType} for '${targetId}'`,
    targetType: "emoji_placement",
    targetId: `${slotType}:${targetId}`,
    metadata: { slotType, targetId, itemId, altEmoji: item.alt_emoji },
  });

  await db.batch([insertStmt, auditStmt]);

  return c.json({
    success: true,
    data: {
      slotType,
      targetId,
      itemId,
      altEmoji: item.alt_emoji,
      updatedBy: user?.email || "admin",
      updatedAt: now,
    },
  });
});

// DELETE /api/admin/emoji/placements - Clear an emoji placement
emojiAdminRoutes.delete("/placements", async (c) => {
  const user = c.get("user");
  const db = c.env.DB;
  const slotType = c.req.query("slotType") || "";
  const targetId = c.req.query("targetId") || "";

  if (!["exam_card", "category_header", "broadcast"].includes(slotType) || !targetId.trim() || targetId.length > 128) {
    return c.json(
      {
        success: false,
        code: "INVALID_EMOJI_PLACEMENT",
        error: "Provide a valid placement type and target ID (1-128 characters).",
      },
      400
    );
  }

  const deleteStmt = db.prepare("DELETE FROM emoji_placements WHERE slot_type = ? AND target_id = ?").bind(slotType, targetId);

  const { stmt: auditStmt } = prepareAdminAuditLog(db, user, {
    actionType: "CLEAR_EMOJI_PLACEMENT",
    description: `Removed emoji placement on ${slotType} for '${targetId}'`,
    targetType: "emoji_placement",
    targetId: `${slotType}:${targetId}`,
    metadata: { slotType, targetId },
    condition: { sql: "changes() > 0", bindings: [] },
  });

  const results = await db.batch([deleteStmt, auditStmt]);
  if ((results[0]?.meta?.changes ?? 0) === 0) {
    return c.json(
      {
        success: false,
        code: "EMOJI_PLACEMENT_NOT_FOUND",
        error: "Emoji placement no longer exists. Refresh and retry.",
      },
      404
    );
  }
  return c.json({ success: true, message: "Placement cleared successfully." });
});
