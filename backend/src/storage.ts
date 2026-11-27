// ============================================================================
// Unified Asset Storage Helper (Cloudflare R2 preferred, Telegram Media Vault)
// ============================================================================

import { TelegramMediaVault } from "./telegramMediaVault";
import { Env } from "./types";

export class AssetStorage {
  private env: Env;
  private telegramVault: TelegramMediaVault;

  constructor(env: Env) {
    this.env = env;
    this.telegramVault = new TelegramMediaVault(env);
  }

  hasR2(): boolean {
    return Boolean(this.env.EMOJI_BUCKET);
  }

  hasTelegramVault(): boolean {
    return this.telegramVault.isConfigured();
  }

  getStorageBackendLabel(): "r2" | "telegram" {
    return this.hasR2() ? "r2" : "telegram";
  }

  getPublicOrWorkerUrl(itemId: string, storageKey: string): string {
    if (storageKey.startsWith("res:raw/")) {
      return storageKey;
    }
    return `/api/emoji/assets/${encodeURIComponent(itemId)}`;
  }

  async uploadFile(path: string, data: ArrayBuffer | Uint8Array, contentType: string): Promise<string> {
    return this.putObject(path, data, contentType);
  }

  async putObject(path: string, data: ArrayBuffer | Uint8Array, contentType: string): Promise<string> {
    const cleanPath = path.replace(/^\/+/, "");
    if (this.env.EMOJI_BUCKET) {
      await this.env.EMOJI_BUCKET.put(cleanPath, data, {
        httpMetadata: { contentType },
      });
      return `r2://${cleanPath}`;
    }
    return this.telegramVault.uploadFile(cleanPath, data, contentType);
  }

  async getObjectBytes(storageRef: string): Promise<{ body: Uint8Array; contentType: string } | null> {
    const trimmed = String(storageRef || "").trim();
    if (!trimmed) {
      return null;
    }
    if (trimmed.startsWith("tg:")) {
      return this.telegramVault.getObjectBytes(trimmed);
    }
    const cleanPath = trimmed.replace(/^r2:\/\//, "").replace(/^\/+/, "");
    if (this.env.EMOJI_BUCKET) {
      const obj = await this.env.EMOJI_BUCKET.get(cleanPath);
      if (!obj) {
        return null;
      }
      const bytes = new Uint8Array(await obj.arrayBuffer());
      const contentType = obj.httpMetadata?.contentType || "application/octet-stream";
      return { body: bytes, contentType };
    }
    return this.telegramVault.getObjectBytes(trimmed);
  }

  async deleteFiles(refs: string[]): Promise<void> {
    const normalized = [
      ...new Set(
        refs
          .map((r) => String(r || "").trim())
          .filter((r) => r && !r.startsWith("res:raw/"))
      ),
    ];
    if (!normalized.length) return;

    const tgRefs = normalized.filter((r) => r.startsWith("tg:"));
    const r2Keys = normalized
      .filter((r) => !r.startsWith("tg:"))
      .map((r) => r.replace(/^r2:\/\//, "").replace(/^\/+/, ""))
      .filter(Boolean);

    if (tgRefs.length) {
      await this.telegramVault.deleteFiles(tgRefs);
    }
    if (r2Keys.length && this.env.EMOJI_BUCKET) {
      await this.env.EMOJI_BUCKET.delete(r2Keys);
    }
  }
}
