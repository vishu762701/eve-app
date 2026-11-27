// ============================================================================
// Telegram Bot API Media Vault (sendDocument / getFile / deleteMessage)
// Canonical ref format: tg:<messageId>:<fileId>
// ============================================================================

import { Env } from "./types";

export const MAX_TELEGRAM_MEDIA_BYTES = 20 * 1024 * 1024;

export class TelegramMediaVaultError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "TelegramMediaVaultError";
    this.status = status;
  }
}

interface TelegramResponse<T = any> {
  ok?: boolean;
  description?: string;
  result?: T;
}

const MIME_BY_EXTENSION: Record<string, string> = {
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  webp: "image/webp",
  gif: "image/gif",
  mp4: "video/mp4",
  webm: "video/webm",
  pdf: "application/pdf",
  json: "application/json",
  tgs: "application/x-tgsticker",
};

const EXT_BY_MIME: Record<string, string> = {
  "image/jpeg": "jpg",
  "image/jpg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "video/mp4": "mp4",
  "video/webm": "webm",
  "application/pdf": "pdf",
  "application/json": "json",
  "application/x-tgsticker": "tgs",
};

function sanitizeUploadFilename(path: string, contentType: string): string {
  const leaf = String(path || "")
    .split("/")
    .pop()
    ?.replace(/[\u0000-\u001f\u007f\\/:*?"<>|]/g, "_")
    .trim();
  if (leaf && leaf.includes(".")) {
    return leaf.slice(0, 120);
  }
  const cleanMime = String(contentType || "").split(";")[0].trim().toLowerCase();
  const ext = EXT_BY_MIME[cleanMime] || "bin";
  const base = leaf || "asset";
  return `${base.slice(0, 100)}.${ext}`;
}

function inferContentTypeFromFilePath(filePath: string, headerContentType: string | null): string {
  const cleanPath = String(filePath || "").split("?")[0].trim();
  const dotIdx = cleanPath.lastIndexOf(".");
  if (dotIdx >= 0 && dotIdx < cleanPath.length - 1) {
    const ext = cleanPath.slice(dotIdx + 1).toLowerCase();
    if (MIME_BY_EXTENSION[ext]) {
      return MIME_BY_EXTENSION[ext];
    }
  }
  const cleanHeader = String(headerContentType || "").split(";")[0].trim();
  return cleanHeader || "application/octet-stream";
}

function extractTelegramFileId(result: any): string {
  if (!result || typeof result !== "object") return "";
  if (result.document?.file_id) return String(result.document.file_id).trim();
  if (result.video?.file_id) return String(result.video.file_id).trim();
  if (result.animation?.file_id) return String(result.animation.file_id).trim();
  if (result.sticker?.file_id) return String(result.sticker.file_id).trim();
  if (Array.isArray(result.photo) && result.photo.length > 0) {
    const largest = result.photo[result.photo.length - 1];
    if (largest?.file_id) return String(largest.file_id).trim();
  }
  if (result.audio?.file_id) return String(result.audio.file_id).trim();
  if (result.voice?.file_id) return String(result.voice.file_id).trim();
  return "";
}

export function parseTelegramStorageRef(
  storageRef: string
): { messageId?: string; fileId: string } | null {
  const trimmed = String(storageRef || "").trim();
  if (!trimmed.startsWith("tg:")) {
    return null;
  }
  const remainder = trimmed.slice(3).trim();
  if (!remainder) {
    return null;
  }
  const firstColon = remainder.indexOf(":");
  if (firstColon > 0) {
    const maybeMessageId = remainder.slice(0, firstColon).trim();
    const fileId = remainder.slice(firstColon + 1).trim();
    if (/^\d+$/.test(maybeMessageId) && fileId) {
      return { messageId: maybeMessageId, fileId };
    }
  }
  return { fileId: remainder };
}

export class TelegramMediaVault {
  private env: Env;

  constructor(env: Env) {
    this.env = env;
  }

  isConfigured(): boolean {
    return Boolean(
      this.env.TELEGRAM_BOT_TOKEN?.trim() && this.env.TELEGRAM_MEDIA_CHANNEL_ID?.trim()
    );
  }

  private getCredentials(): { token: string; chatId: string } {
    const token = this.env.TELEGRAM_BOT_TOKEN?.trim() || "";
    const chatId = this.env.TELEGRAM_MEDIA_CHANNEL_ID?.trim() || "";
    if (!token || !chatId) {
      throw new TelegramMediaVaultError(
        500,
        "Telegram Media Vault is not configured on the Worker. Please set TELEGRAM_BOT_TOKEN and TELEGRAM_MEDIA_CHANNEL_ID."
      );
    }
    return { token, chatId };
  }

  async uploadFile(
    path: string,
    data: ArrayBuffer | Uint8Array,
    contentType: string
  ): Promise<string> {
    const { token, chatId } = this.getCredentials();
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    if (bytes.byteLength <= 0) {
      throw new TelegramMediaVaultError(400, "Media file cannot be empty.");
    }
    if (bytes.byteLength > MAX_TELEGRAM_MEDIA_BYTES) {
      throw new TelegramMediaVaultError(
        400,
        "File exceeds Telegram Bot API 20 MB upload limit."
      );
    }

    const mime = String(contentType || "application/octet-stream").split(";")[0].trim() || "application/octet-stream";
    const filename = sanitizeUploadFilename(path, mime);
    const arrayBuffer = bytes.buffer.slice(
      bytes.byteOffset,
      bytes.byteOffset + bytes.byteLength
    ) as ArrayBuffer;

    const form = new FormData();
    form.append("chat_id", chatId);
    form.append("document", new Blob([arrayBuffer], { type: mime }), filename);
    form.append("caption", String(path || filename).slice(0, 1024));

    const res = await fetch(`https://api.telegram.org/bot${token}/sendDocument`, {
      method: "POST",
      body: form,
    });

    const json = (await res.json().catch(() => null)) as TelegramResponse | null;
    if (!res.ok || !json?.ok) {
      const desc = json?.description || `HTTP ${res.status}`;
      throw new TelegramMediaVaultError(502, `Telegram Media Vault upload failed: ${desc}`);
    }

    const messageId = json.result?.message_id;
    const fileId = extractTelegramFileId(json.result);
    if (!messageId || !fileId) {
      throw new TelegramMediaVaultError(
        502,
        "Telegram Media Vault response missing message_id or file_id."
      );
    }

    return `tg:${messageId}:${fileId}`;
  }

  async getObjectBytes(
    storageRef: string
  ): Promise<{ body: Uint8Array; contentType: string } | null> {
    const parsed = parseTelegramStorageRef(storageRef);
    if (!parsed) {
      return null;
    }

    const token = this.env.TELEGRAM_BOT_TOKEN?.trim() || "";
    if (!token) {
      return null;
    }

    const metaRes = await fetch(
      `https://api.telegram.org/bot${token}/getFile?file_id=${encodeURIComponent(parsed.fileId)}`
    );
    if (!metaRes.ok) {
      return null;
    }
    const metaJson = (await metaRes.json().catch(() => null)) as TelegramResponse<{
      file_path?: string;
    }> | null;
    const filePath = metaJson?.ok ? String(metaJson.result?.file_path || "").trim() : "";
    if (!filePath) {
      return null;
    }

    const cleanFilePath = filePath.replace(/^\/+/, "");
    const fileRes = await fetch(`https://api.telegram.org/file/bot${token}/${cleanFilePath}`);
    if (!fileRes.ok) {
      return null;
    }

    const body = new Uint8Array(await fileRes.arrayBuffer());
    const contentType = inferContentTypeFromFilePath(
      cleanFilePath,
      fileRes.headers.get("content-type")
    );
    return { body, contentType };
  }

  async deleteFile(storageRef: string): Promise<void> {
    const parsed = parseTelegramStorageRef(storageRef);
    if (!parsed || !parsed.messageId) {
      return;
    }

    const { token, chatId } = this.getCredentials();
    const res = await fetch(`https://api.telegram.org/bot${token}/deleteMessage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: chatId,
        message_id: Number(parsed.messageId),
      }),
    });

    const rawText = await res.text().catch(() => "");
    let json: TelegramResponse | null = null;
    if (rawText.trim()) {
      try {
        json = JSON.parse(rawText) as TelegramResponse;
      } catch {}
    }

    if (res.ok && (!json || json.ok !== false)) {
      return;
    }

    const desc = String(json?.description || rawText || `HTTP ${res.status}`);
    if (desc.toLowerCase().includes("message to delete not found")) {
      return;
    }
    throw new TelegramMediaVaultError(502, `Telegram Media Vault delete failed: ${desc}`);
  }

  async deleteFiles(refs: string[]): Promise<void> {
    const unique = [
      ...new Set(
        refs
          .map((ref) => String(ref || "").trim())
          .filter((ref) => Boolean(parseTelegramStorageRef(ref)?.messageId))
      ),
    ];
    for (const ref of unique) {
      await this.deleteFile(ref);
    }
  }
}
