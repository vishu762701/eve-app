-- Migration 0038: Lookup indexes for unified Telegram phone and telegram_user_id identity sync
CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone);
CREATE INDEX IF NOT EXISTS idx_users_telegram_user_id ON users (telegram_user_id);
