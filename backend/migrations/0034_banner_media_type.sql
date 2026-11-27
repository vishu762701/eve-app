-- Additive media_type column for home_banners supporting image, GIF, WebP, MP4, WebM.
-- Defensive PRAGMA table_info checks keep pre-0034 live D1 tables safe before migration runs.
ALTER TABLE home_banners ADD COLUMN media_type TEXT NOT NULL DEFAULT 'image';
