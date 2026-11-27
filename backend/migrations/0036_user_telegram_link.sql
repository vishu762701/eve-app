-- Link EVE student accounts with optional Telegram MTProto user identity
ALTER TABLE users ADD COLUMN telegram_user_id TEXT;
ALTER TABLE users ADD COLUMN telegram_username TEXT;
