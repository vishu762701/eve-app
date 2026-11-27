// ============================================================================
// Shared Media Avatar Validation, Upload Parsing & Asset Serving Helpers
// ============================================================================

import { Context } from "hono";
import { AssetStorage } from "../storage";
import { Env } from "../types";

export const MAX_AVATAR_BYTES = 5 * 1024 * 1024;
const ALLOWED_AVATAR_MIMES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
]);

export async function getTableColumns(
  db: D1Database,
  tableName: string
): Promise<Set<string>> {
  const cols = new Set<string>();
  try {
    const { results } = await db
      .prepare(`PRAGMA table_info(${tableName})`)
      .all<{ name: string }>();
    for (const row of results || []) {
      if (row?.name) cols.add(String(row.name).trim());
    }
  } catch {
    // Callers treat empty set safely via defensive fallbacks.
  }
  return cols;
}

export function detectAvatarImage(
  bytes: Uint8Array,
  declaredMime?: string
): { mime: string; ext: string } | null {
  if (bytes.length < 12 || bytes.length > MAX_AVATAR_BYTES) return null;
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return { mime: "image/jpeg", ext: "jpg" };
  }
  if (
    bytes[0] === 0x89 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x4e &&
    bytes[3] === 0x47
  ) {
    return { mime: "image/png", ext: "png" };
  }
  if (
    bytes[0] === 0x47 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x38
  ) {
    return { mime: "image/gif", ext: "gif" };
  }
  if (
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return { mime: "image/webp", ext: "webp" };
  }
  const cleanMime = String(declaredMime || "")
    .split(";")[0]
    .trim()
    .toLowerCase();
  if (ALLOWED_AVATAR_MIMES.has(cleanMime)) {
    const ext =
      cleanMime === "image/png"
        ? "png"
        : cleanMime === "image/webp"
        ? "webp"
        : cleanMime === "image/gif"
        ? "gif"
        : "jpg";
    return { mime: cleanMime, ext };
  }
  return null;
}

export function extractStorageAvatarKey(rawUrl: string, prefix: string): string {
  const value = (rawUrl || "").trim();
  if (!value) return "";
  if (value.startsWith("r2://")) {
    return value.slice("r2://".length).replace(/^\/+/, "");
  }
  if (value.includes("/eve-media/")) {
    return value.split("/eve-media/")[1]?.split("?")[0]?.replace(/^\/+/, "") || "";
  }
  if (value.includes("/avatar")) {
    try {
      const parsed = new URL(value, "https://eve.local");
      const keyParam = parsed.searchParams.get("k");
      if (keyParam && keyParam.startsWith(prefix)) {
        return keyParam;
      }
    } catch {}
  }
  return "";
}

export async function parseAvatarUploadPayload(
  c: Context
): Promise<{
  imageBytes: Uint8Array | null;
  declaredMime: string;
  parsedBody: Record<string, unknown> | null;
  error?: string;
}> {
  const contentType = (c.req.header("content-type") || "").toLowerCase();
  let imageBytes: Uint8Array | null = null;
  let declaredMime = "";
  let parsedBody: Record<string, unknown> | null = null;

  if (contentType.includes("multipart/form-data")) {
    const formData = await c.req.formData();
    const fileValue: unknown = formData.get("file");
    if (
      fileValue &&
      typeof fileValue === "object" &&
      "arrayBuffer" in fileValue &&
      typeof fileValue.arrayBuffer === "function"
    ) {
      const file = fileValue as File;
      declaredMime = file.type || "";
      imageBytes = new Uint8Array(await file.arrayBuffer());
    } else {
      return {
        imageBytes: null,
        declaredMime: "",
        parsedBody: null,
        error: "No avatar image file provided",
      };
    }
  } else if (contentType.startsWith("image/")) {
    declaredMime = contentType.split(";")[0];
    imageBytes = new Uint8Array(await c.req.arrayBuffer());
  } else {
    const rawJson = await c.req.json().catch(() => null);
    if (!rawJson || typeof rawJson !== "object" || Array.isArray(rawJson)) {
      return {
        imageBytes: null,
        declaredMime: "",
        parsedBody: null,
        error: "Provide an avatar image or emoji payload",
      };
    }
    parsedBody = rawJson as Record<string, unknown>;
    if (typeof parsedBody.base64 === "string" && parsedBody.base64.trim()) {
      const rawB64 = parsedBody.base64.trim();
      const commaIdx = rawB64.indexOf(",");
      const b64Clean =
        rawB64.startsWith("data:") && commaIdx > 0
          ? rawB64.slice(commaIdx + 1)
          : rawB64;
      if (b64Clean.length > Math.ceil((MAX_AVATAR_BYTES * 4) / 3) + 16) {
        return {
          imageBytes: null,
          declaredMime: "",
          parsedBody: null,
          error: "Avatar image exceeds 5MB limit",
        };
      }
      try {
        const bin = atob(b64Clean);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        imageBytes = bytes;
      } catch {
        return {
          imageBytes: null,
          declaredMime: "",
          parsedBody: null,
          error: "Invalid base64 image encoding",
        };
      }
      declaredMime =
        typeof parsedBody.mimeType === "string" ? parsedBody.mimeType : "";
    }
  }

  return { imageBytes, declaredMime, parsedBody };
}

export async function serveStoredAvatarResponse(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  storedUrl: string,
  prefix: string
) {
  const key =
    extractStorageAvatarKey(storedUrl, prefix) ||
    extractStorageAvatarKey(c.req.url, prefix);
  if (!key) {
    return c.json({ success: false, error: "Avatar image not set" }, 404);
  }
  const storage = new AssetStorage(c.env);
  const asset = await storage.getObjectBytes(key);
  if (!asset) {
    return c.json({ success: false, error: "Avatar media not found" }, 404);
  }
  return c.body(
    asset.body.buffer.slice(
      asset.body.byteOffset,
      asset.body.byteOffset + asset.body.byteLength
    ) as ArrayBuffer,
    200,
    {
      "Content-Type": asset.contentType || "image/jpeg",
      "Cache-Control": "public, max-age=86400",
    }
  );
}
