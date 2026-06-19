-- 사용자별 TOTP 등록 (user_credential처럼 분리 테이블)
CREATE TABLE user_totp (
  user_id      TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  secret_enc   TEXT NOT NULL,
  enabled      INTEGER NOT NULL DEFAULT 0,
  confirmed_at TEXT,
  last_step    INTEGER NOT NULL DEFAULT 0,
  created_at   TEXT NOT NULL
);

-- 이메일 1회용 복구 코드 (PBKDF2 해시 + salt, 단기 만료)
CREATE TABLE totp_recovery (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  salt       TEXT NOT NULL,
  code_hash  TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used       INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_totp_recovery_user ON totp_recovery(user_id);

-- admin 유예 추적
ALTER TABLE app_user ADD COLUMN totp_grace_start TEXT;

-- 정책
INSERT INTO app_setting (key, value) VALUES ('2fa.grace_days', '7');
