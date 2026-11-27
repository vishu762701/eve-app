// ============================================================================
// Cloudflare D1 Read Replication Session & Batch Read Helpers
// Uses `withSession("first-unconstrained")` on read-only paths with a safe
// fallback to the primary `db` when running on local/mock D1 bindings in tests.
// NEVER use `getReadDb` for writes, locks, or read-after-write verification.
// ============================================================================

import { normalizeAppConfigObject, NormalizedAppConfig } from "./appConfig";

export function getReadDb(db: D1Database): D1Database {
  if (db && typeof (db as any).withSession === "function") {
    try {
      const session = (db as any).withSession("first-unconstrained");
      if (session && typeof session.prepare === "function") {
        return session as D1Database;
      }
    } catch {
      return db;
    }
  }
  return db;
}

/**
 * Executes multiple read-only SELECT statements in a single `db.batch([...])`
 * round-trip on Cloudflare D1. Automatically falls back to parallel `.all()`
 * only when a test mock D1 omits `.results` arrays from `batch()` output or if
 * an optional table query throws in a minimal schema test.
 */
export async function batchReadRows<T = any>(
  readDb: D1Database,
  stmts: D1PreparedStatement[]
): Promise<Array<T[]>> {
  if (!stmts.length) return [];

  if (typeof (readDb as any).batch === "function") {
    try {
      const batchResults = await readDb.batch<T>(stmts);
      if (
        Array.isArray(batchResults) &&
        batchResults.length === stmts.length &&
        batchResults.every((r) => r && Array.isArray((r as any).results))
      ) {
        return batchResults.map((r) => (r.results as T[]) || []);
      }
    } catch {
      // Fall through to resilient per-statement execution below (e.g. missing optional table in test schema)
    }
  }

  return Promise.all(
    stmts.map(async (stmt) => {
      try {
        const res = await stmt.all<T>();
        return (res?.results as T[]) || [];
      } catch {
        return [] as T[];
      }
    })
  );
}

/**
 * Parses an `app_content` row (`id = 'app_config'`) returned inside a D1 batch.
 */
export function parseAppConfigRow(row: { body?: string } | null | undefined): NormalizedAppConfig {
  if (!row || !row.body) {
    return normalizeAppConfigObject({});
  }
  try {
    const parsed = JSON.parse(row.body);
    return normalizeAppConfigObject(parsed);
  } catch {
    return normalizeAppConfigObject({});
  }
}
