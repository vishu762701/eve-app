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
        return cached;
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

  return response;
}
