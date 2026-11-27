-- Additive exam avatar columns supporting uploaded image/GIF/WebP or emoji + color avatar.
-- Defensive PRAGMA table_info checks keep existing live D1 queries safe before migration is applied.
ALTER TABLE exams ADD COLUMN avatar_url TEXT NOT NULL DEFAULT '';
ALTER TABLE exams ADD COLUMN avatar_emoji TEXT NOT NULL DEFAULT '';
ALTER TABLE exams ADD COLUMN avatar_color INTEGER DEFAULT NULL;
