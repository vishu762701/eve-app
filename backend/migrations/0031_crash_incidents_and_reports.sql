-- Migration 0031: Crash Telemetry and Incident Index for Eve Admin Crash Center (Mission 00B)

CREATE TABLE IF NOT EXISTS crash_signatures (
  signature TEXT PRIMARY KEY,
  exception_class TEXT NOT NULL,
  top_frame TEXT NOT NULL,
  sanitized_message TEXT,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  occurrence_count INTEGER DEFAULT 1,
  status TEXT DEFAULT 'open',
  suggested_cause TEXT DEFAULT 'no rule matched'
);

CREATE TABLE IF NOT EXISTS crash_incidents (
  id TEXT PRIMARY KEY,
  signature TEXT NOT NULL,
  app_version_name TEXT,
  app_version_code INTEGER,
  android_sdk INTEGER,
  device_model TEXT,
  anr_or_exit_reason TEXT,
  sanitized_frames_json TEXT,
  install_id_hash TEXT,
  timestamp INTEGER NOT NULL,
  is_synthetic INTEGER DEFAULT 0,
  FOREIGN KEY (signature) REFERENCES crash_signatures(signature)
);

CREATE INDEX IF NOT EXISTS idx_crash_signatures_last_seen ON crash_signatures(last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_crash_signatures_status ON crash_signatures(status);
CREATE INDEX IF NOT EXISTS idx_crash_incidents_sig_time ON crash_incidents(signature, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_crash_incidents_install_time ON crash_incidents(install_id_hash, timestamp);
