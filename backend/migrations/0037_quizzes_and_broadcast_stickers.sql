-- Additive D1 migration for official Telegram-style Quizzes and Broadcast Animated Stickers
ALTER TABLE polls ADD COLUMN is_quiz INTEGER NOT NULL DEFAULT 1;
ALTER TABLE polls ADD COLUMN correct_option_index INTEGER NOT NULL DEFAULT -1;
ALTER TABLE polls ADD COLUMN explanation TEXT NOT NULL DEFAULT '';

ALTER TABLE notifications ADD COLUMN sticker_doc_id TEXT NOT NULL DEFAULT '';
ALTER TABLE notifications ADD COLUMN sticker_access_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE notifications ADD COLUMN sticker_dc_id INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notifications ADD COLUMN sticker_emoji TEXT NOT NULL DEFAULT '';
