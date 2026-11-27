// ============================================================================
// Shared Cloudflare Edge Cache Helper for Worker Media Proxy Routes
// ============================================================================

import { Context } from "hono";
import { AssetStorage } from "../storage";
import { Env } from "../types";

export const PUBLIC_MEDIA_CACHE_CONTROL = "public, max-age=86400";

function getEdgeCache(): Cache | null {
  try {
    const globalCaches = (globalThis as any).caches;
    if (globalCaches && globalCaches.default && typeof globalCaches.default.match === "function") {
      return globalCaches.default as Cache;
    }
  } catch {}
  return null;
}

function parseSingleByteRange(
  rangeHeader: string,
  totalLength: number
): { start: number; end: number } | "invalid" {
  const trimmed = rangeHeader.trim();
  const match = /^bytes=([^,]+)$/i.exec(trimmed);
  if (!match) return "invalid";
  const spec = match[1].trim();
  const dashIdx = spec.indexOf("-");
  if (dashIdx < 0) return "invalid";
  const startStr = spec.slice(0, dashIdx).trim();
  const endStr = spec.slice(dashIdx + 1).trim();
  if (!startStr && !endStr) return "invalid";

  if (!startStr) {
    // Suffix byte-range-spec: bytes=-500
    if (!/^[0-9]+$/.test(endStr)) return "invalid";
    const suffixLength = Number(endStr);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0 || totalLength <= 0) {
      return "invalid";
    }
    const start = Math.max(0, totalLength - suffixLength);
    return { start, end: totalLength - 1 };
  }

  if (!/^[0-9]+$/.test(startStr)) return "invalid";
  const start = Number(startStr);
  if (!Number.isSafeInteger(start) || start < 0 || start >= totalLength) {
    return "invalid";
  }

  if (!endStr) {
    return { start, end: totalLength - 1 };
  }
  if (!/^[0-9]+$/.test(endStr)) return "invalid";
  const rawEnd = Number(endStr);
  if (!Number.isSafeInteger(rawEnd) || rawEnd < start) {
    return "invalid";
  }
  const end = Math.min(rawEnd, totalLength - 1);
  return { start, end };
}

async function applyRangeSupport(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  fullResponse: Response
): Promise<Response> {
  const rangeHeader = c.req.header("range") || c.req.header("Range");
  const fullBytes = new Uint8Array(await fullResponse.arrayBuffer());
  const totalLength = fullBytes.byteLength;
  const baseHeaders = new Headers(fullResponse.headers);
  baseHeaders.set("Accept-Ranges", "bytes");

  if (!rangeHeader) {
    baseHeaders.set("Content-Length", String(totalLength));
    return new Response(fullBytes, { status: 200, headers: baseHeaders });
  }

  const parsed = parseSingleByteRange(rangeHeader, totalLength);
  if (parsed === "invalid") {
    baseHeaders.set("Content-Range", `bytes */${totalLength}`);
    baseHeaders.set("Content-Length", "0");
    return new Response(null, { status: 416, headers: baseHeaders });
  }

  const slice = fullBytes.subarray(parsed.start, parsed.end + 1);
  baseHeaders.set("Content-Range", `bytes ${parsed.start}-${parsed.end}/${totalLength}`);
  baseHeaders.set("Content-Length", String(slice.byteLength));
  return new Response(slice, { status: 206, headers: baseHeaders });
}

export async function serveCachedStorageMedia(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  storageRef: string,
  options?: {
    notFoundMessage?: string;
    fallbackContentType?: string;
    contentDisposition?: string;
    extraHeaders?: Record<string, string>;
  }
): Promise<Response> {
  const edgeCache = getEdgeCache();
  let cacheKey: Request | null = null;
  if (edgeCache) {
    try {
      const keyUrl = new URL(c.req.url, "https://eve-cache.internal");
      keyUrl.searchParams.set("__ref", storageRef);
      cacheKey = new Request(keyUrl.toString(), { method: "GET" });
      const cached = await edgeCache.match(cacheKey);
      if (cached) {
        return applyRangeSupport(c, cached);
      }
    } catch {}
  }

  const storage = new AssetStorage(c.env);
  const asset = await storage.getObjectBytes(storageRef);
  if (!asset) {
    return c.json({ success: false, error: options?.notFoundMessage || "Media not found" }, 404);
  }

  const headers: Record<string, string> = {
    "Content-Type": asset.contentType || options?.fallbackContentType || "application/octet-stream",
    "Cache-Control": PUBLIC_MEDIA_CACHE_CONTROL,
    "Accept-Ranges": "bytes",
    "Content-Length": String(asset.body.byteLength),
    ...(options?.contentDisposition ? { "Content-Disposition": options.contentDisposition } : {}),
    ...(options?.extraHeaders || {}),
  };

  const payload = asset.body.buffer.slice(
    asset.body.byteOffset,
    asset.body.byteOffset + asset.body.byteLength
  ) as ArrayBuffer;
  const response = new Response(payload, { status: 200, headers });

  if (edgeCache && cacheKey && typeof edgeCache.put === "function") {
    const putPromise = edgeCache.put(cacheKey, response.clone()).catch(() => {});
    try {
      const ctx = c.executionCtx;
      if (ctx && typeof ctx.waitUntil === "function") {
        ctx.waitUntil(putPromise);
      } else {
        await putPromise;
      }
    } catch {
      await putPromise;
    }
  }

  return applyRangeSupport(c, response);
}
