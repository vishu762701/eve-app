-- Migration 0032: Complete Emoji & Sticker Pack Library (D1 metadata + R2/Asset manifest)
ALTER TABLE emoji_packs ADD COLUMN pack_type TEXT NOT NULL DEFAULT 'emoji';
ALTER TABLE emoji_packs ADD COLUMN description TEXT DEFAULT '';
ALTER TABLE emoji_packs ADD COLUMN category TEXT NOT NULL DEFAULT 'General';
ALTER TABLE emoji_packs ADD COLUMN featured INTEGER NOT NULL DEFAULT 0;
ALTER TABLE emoji_packs ADD COLUMN thumbnail_key TEXT DEFAULT '';
ALTER TABLE emoji_packs ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE emoji_packs ADD COLUMN creator_attribution TEXT DEFAULT '';
ALTER TABLE emoji_packs ADD COLUMN rights_status TEXT NOT NULL DEFAULT 'approved';
ALTER TABLE emoji_packs ADD COLUMN license_evidence TEXT DEFAULT '';
ALTER TABLE emoji_packs ADD COLUMN publication_status TEXT NOT NULL DEFAULT 'published';
ALTER TABLE emoji_packs ADD COLUMN access_tier TEXT NOT NULL DEFAULT 'free';
ALTER TABLE emoji_packs ADD COLUMN import_status TEXT NOT NULL DEFAULT 'complete';
ALTER TABLE emoji_packs ADD COLUMN import_expected_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE emoji_packs ADD COLUMN import_failed_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE emoji_packs ADD COLUMN import_error_summary TEXT DEFAULT '';
ALTER TABLE emoji_packs ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;

ALTER TABLE emoji_items ADD COLUMN item_index INTEGER NOT NULL DEFAULT 0;
ALTER TABLE emoji_items ADD COLUMN file_format TEXT NOT NULL DEFAULT 'lottie';
ALTER TABLE emoji_items ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'application/json';
ALTER TABLE emoji_items ADD COLUMN is_animated INTEGER NOT NULL DEFAULT 1;
ALTER TABLE emoji_items ADD COLUMN is_video INTEGER NOT NULL DEFAULT 0;
ALTER TABLE emoji_items ADD COLUMN thumbnail_sha256 TEXT DEFAULT '';
ALTER TABLE emoji_items ADD COLUMN external_reference TEXT DEFAULT '';
ALTER TABLE emoji_items ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;

UPDATE emoji_packs
SET pack_type = 'emoji',
    category = CASE WHEN id = 'pack_tg_ui' THEN 'UI' WHEN id = 'pack_noto' THEN 'Animated' ELSE 'General' END,
    featured = CASE WHEN id IN ('pack_tg_ui', 'pack_noto') THEN 1 ELSE 0 END,
    rights_status = 'approved',
    publication_status = 'published',
    access_tier = 'free',
    updated_at = COALESCE(NULLIF(updated_at, 0), created_at)
WHERE id IN ('pack_tg_ui', 'pack_noto') OR updated_at = 0;

CREATE INDEX IF NOT EXISTS idx_emoji_packs_catalog ON emoji_packs (enabled, publication_status, rights_status, pack_type, featured, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_emoji_items_order ON emoji_items (pack_id, enabled, item_index ASC, created_at ASC);
