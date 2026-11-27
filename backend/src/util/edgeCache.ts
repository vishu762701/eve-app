// ============================================================================
// Cloudflare Edge Cache Helper for Student-Safe Public/Catalog JSON GETs
// Strictly isolated from Admin/private payloads and purged on Admin mutations.
// ============================================================================

import { Context } from "hono";
import { Env } from "../types";

export const STUDENT_EDGE_CACHE_CONTROL = "public, max-age=30, stale-while-revalidate=60";

const trackedCacheKeys = new Set<string>([
  "https://eve-edge-cache.internal/api/exams",
  "https://eve-edge-cache.internal/api/banners",
  "https://eve-edge-cache.internal/api/app-config",
  "https://eve-edge-cache.internal/api/generated-tests",
  "https://eve-edge-cache.internal/api/leaderboard?examId=overall&limit=50",
]);

function resolveDefaultCache(): Cache | null {
  try {
    const globalCaches = (globalThis as any).caches;
    if (globalCaches && globalCaches.default && typeof globalCaches.default.match === "function") {
      return globalCaches.default as Cache;
    }
  } catch {}
  return null;
}

export function buildStudentEdgeCacheUrl(reqUrl: string): string {
  const parsed = new URL(reqUrl, "https://eve-edge-cache.internal");
  const target = new URL(`https://eve-edge-cache.internal${parsed.pathname}`);
  const params = Array.from(parsed.searchParams.entries()).sort(([ka, va], [kb, vb]) =>
    ka === kb ? va.localeCompare(vb) : ka.localeCompare(kb)
  );
  for (const [k, v] of params) {
    target.searchParams.append(k, v);
  }
  return target.toString();
}

function isEligibleStudentGet(c: Context<{ Bindings: Env; Variables: any }, any, any>): boolean {
  if (c.req.method !== "GET") {
    return false;
  }
  try {
    const user = c.get("user");
    if (user?.isAdmin) {
      return false;
    }
  } catch {}
  return true;
}

function runDeferred(
  c: Context<{ Bindings: Env; Variables: any }, any, any> | null | undefined,
  promise: Promise<unknown>
): Promise<unknown> | void {
  const safe = promise.catch(() => {});
  if (c) {
    try {
      const ctx = c.executionCtx;
      if (ctx && typeof ctx.waitUntil === "function") {
        ctx.waitUntil(safe);
        return;
      }
    } catch {}
  }
  return safe;
}

export async function matchStudentEdgeCache(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  cacheOverride?: Cache | null
): Promise<Response | null> {
  if (!isEligibleStudentGet(c)) {
    return null;
  }
  const cache = cacheOverride !== undefined ? cacheOverride : resolveDefaultCache();
  if (!cache) {
    return null;
  }
  try {
    const keyUrl = buildStudentEdgeCacheUrl(c.req.url);
    const req = new Request(keyUrl, { method: "GET" });
    const cached = await cache.match(req);
    if (!cached) {
      return null;
    }
    const headers = new Headers(cached.headers);
    headers.set("X-Eve-Edge-Cache", "HIT");
    return new Response(cached.body, {
      status: cached.status,
      headers,
    });
  } catch {
    return null;
  }
}

export async function storeStudentEdgeCache(
  c: Context<{ Bindings: Env; Variables: any }, any, any>,
  response: Response,
  cacheOverride?: Cache | null
): Promise<Response> {
  if (!isEligibleStudentGet(c) || response.status !== 200) {
    const headers = new Headers(response.headers);
    if (!headers.has("Cache-Control")) {
      headers.set("Cache-Control", "no-store");
    }
    return new Response(response.body, { status: response.status, headers });
  }

  const headers = new Headers(response.headers);
  headers.set("Cache-Control", STUDENT_EDGE_CACHE_CONTROL);
  headers.set("X-Eve-Edge-Cache", "MISS");
  const outResponse = new Response(response.body, {
    status: response.status,
    headers,
  });

  const cache = cacheOverride !== undefined ? cacheOverride : resolveDefaultCache();
  if (cache && typeof cache.put === "function") {
    try {
      const keyUrl = buildStudentEdgeCacheUrl(c.req.url);
      trackedCacheKeys.add(keyUrl);
      const req = new Request(keyUrl, { method: "GET" });
      await runDeferred(c, cache.put(req, outResponse.clone()));
    } catch {}
  }

  return outResponse;
}

export async function purgeEdgeCache(
  c: Context<{ Bindings: Env; Variables: any }, any, any> | null | undefined,
  prefixes: string[],
  cacheOverride?: Cache | null
): Promise<void> {
  const cache = cacheOverride !== undefined ? cacheOverride : resolveDefaultCache();
  const normalizedPrefixes = prefixes
    .map((p) => String(p || "").trim())
    .filter(Boolean)
    .map((p) => (p.startsWith("/") ? p : `/api/${p}`));

  if (!normalizedPrefixes.length) {
    return;
  }

  const urlsToDelete = new Set<string>();
  for (const p of normalizedPrefixes) {
    urlsToDelete.add(`https://eve-edge-cache.internal${p}`);
  }
  for (const existing of trackedCacheKeys) {
    try {
      const path = new URL(existing).pathname;
      if (normalizedPrefixes.some((prefix) => path === prefix || path.startsWith(prefix))) {
        urlsToDelete.add(existing);
      }
    } catch {}
  }

  if (!cache || typeof cache.delete !== "function") {
    return;
  }

  const deletes = Array.from(urlsToDelete).map((url) => {
    trackedCacheKeys.delete(url);
    return cache.delete(new Request(url, { method: "GET" })).catch(() => false);
  });

  await runDeferred(c, Promise.all(deletes));
}
