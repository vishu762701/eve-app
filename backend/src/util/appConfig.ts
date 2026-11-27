// ============================================================================
// Shared App Config & Maintenance State Reader
// ============================================================================

export const DEFAULT_MAINTENANCE_MESSAGE =
  "Eve Mock Test is currently undergoing scheduled maintenance. Please check back shortly.";
export const DEFAULT_ANNOUNCEMENT_CHANNEL_NAME = "Announcements";

export interface NormalizedAppConfig {
  minimum_supported_version_code: number;
  maintenance_mode: boolean;
  maintenance_message: string;
  default_publish_mode: string;
  announcement_channel_name: string;
  announcement_channel_avatar_url: string;
  minSupportedVersionCode: number;
  maintenanceMode: boolean;
  maintenanceMessage: string;
  defaultPublishMode: string;
  announcementChannelName: string;
  announcementChannelAvatarUrl: string;
}

export function normalizeAppConfigObject(raw: Record<string, any> | null | undefined): Record<string, any> & NormalizedAppConfig {
  const src = raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
  const minRaw = src.minimum_supported_version_code ?? src.minSupportedVersionCode;
  const minVer =
    typeof minRaw === "number" && Number.isFinite(minRaw) && minRaw >= 0
      ? Math.floor(minRaw)
      : 1;

  const maintRaw = src.maintenance_mode ?? src.maintenanceMode;
  const maintenanceMode = Boolean(maintRaw === true);

  const msgRaw = src.maintenance_message ?? src.maintenanceMessage;
  const maintenanceMessage =
    typeof msgRaw === "string" && msgRaw.length > 0 ? msgRaw : DEFAULT_MAINTENANCE_MESSAGE;

  const pubRaw = typeof (src.default_publish_mode ?? src.defaultPublishMode) === "string"
    ? String(src.default_publish_mode ?? src.defaultPublishMode).trim().toLowerCase()
    : "";
  const defaultPublishMode =
    pubRaw === "live" || pubRaw === "paused" || pubRaw === "draft" ? pubRaw : "paused";

  const chNameRaw = src.announcement_channel_name ?? src.announcementChannelName;
  const announcementChannelName =
    typeof chNameRaw === "string" && chNameRaw.trim()
      ? chNameRaw.trim().slice(0, 60)
      : DEFAULT_ANNOUNCEMENT_CHANNEL_NAME;

  const chAvatarRaw = src.announcement_channel_avatar_url ?? src.announcementChannelAvatarUrl;
  const announcementChannelAvatarUrl =
    typeof chAvatarRaw === "string" ? chAvatarRaw.trim() : "";

  return {
    ...src,
    minimum_supported_version_code: minVer,
    maintenance_mode: maintenanceMode,
    maintenance_message: maintenanceMessage,
    default_publish_mode: defaultPublishMode,
    announcement_channel_name: announcementChannelName,
    announcement_channel_avatar_url: announcementChannelAvatarUrl,
    minSupportedVersionCode: minVer,
    maintenanceMode,
    maintenanceMessage,
    defaultPublishMode,
    announcementChannelName,
    announcementChannelAvatarUrl,
  };
}

export async function readAppConfig(db: D1Database): Promise<NormalizedAppConfig> {
  const row = await db
    .prepare("SELECT body FROM app_content WHERE id = 'app_config'")
    .first<{ body: string }>();
  if (row && row.body) {
    const parsed = JSON.parse(row.body);
    if (
      !parsed ||
      typeof parsed !== "object" ||
      Array.isArray(parsed) ||
      (parsed.maintenance_mode !== undefined && typeof parsed.maintenance_mode !== "boolean")
    ) {
      throw new Error("Invalid stored configuration");
    }
    return normalizeAppConfigObject(parsed);
  }
  return normalizeAppConfigObject({});
}
