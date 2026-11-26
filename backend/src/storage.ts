// ============================================================================
// Unified Asset Storage Helper (Cloudflare R2 preferred, Supabase fallback)
// ============================================================================

import { SupabaseStorage } from "./supabase";
import { Env } from "./types";

export class AssetStorage {
  private env: Env;
  private supabase: SupabaseStorage;

  constructor(env: Env) {
    this.env = env;
    this.supabase = new SupabaseStorage(env);
  }

  hasR2(): boolean {
    return Boolean(this.env.EMOJI_BUCKET);
  }

  getPublicOrWorkerUrl(itemId: string, storageKey: string): string {
    if (storageKey.startsWith("res:raw/")) {
      return storageKey;
    }
    if (this.hasR2()) {
      return `/api/emoji/assets/${encodeURIComponent(itemId)}`;
    }
    return this.supabase.getPublicUrl(storageKey);
  }

  async putObject(path: string, data: ArrayBuffer | Uint8Array, contentType: string): Promise<string> {
    const cleanPath = path.replace(/^\/+/, "");
    if (this.env.EMOJI_BUCKET) {
      await this.env.EMOJI_BUCKET.put(cleanPath, data, {
        httpMetadata: { contentType },
      });
      return `r2://${cleanPath}`;
    }
    return this.supabase.uploadFile(cleanPath, data, contentType);
  }

  async getObjectBytes(path: string): Promise<{ body: Uint8Array; contentType: string } | null> {
    const cleanPath = path.replace(/^\/+/, "");
    if (this.env.EMOJI_BUCKET) {
      const obj = await this.env.EMOJI_BUCKET.get(cleanPath);
      if (!obj) {
        return null;
      }
      const bytes = new Uint8Array(await obj.arrayBuffer());
      const contentType = obj.httpMetadata?.contentType || "application/octet-stream";
      return { body: bytes, contentType };
    }
    const url = this.supabase.getPublicUrl(cleanPath);
    const res = await fetch(url);
    if (!res.ok) {
      return null;
    }
    const bytes = new Uint8Array(await res.arrayBuffer());
    return { body: bytes, contentType: res.headers.get("content-type") || "application/octet-stream" };
  }

  async deleteFiles(paths: string[]): Promise<void> {
    const clean = [...new Set(paths.map((p) => p.replace(/^\/+/, "")).filter((p) => p && !p.startsWith("res:raw/")))];
    if (!clean.length) return;
    if (this.env.EMOJI_BUCKET) {
      await this.env.EMOJI_BUCKET.delete(clean);
      return;
    }
    await this.supabase.deleteFiles(clean);
  }
}
