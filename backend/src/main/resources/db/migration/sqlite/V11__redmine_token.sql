-- 사용자별 Redmine API 키 (user_totp처럼 분리 테이블, 시드와 동일 AES 보관)
CREATE TABLE user_redmine_token (
  user_id          TEXT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  token_enc        TEXT NOT NULL,            -- AES-256-GCM(base64(nonce||ct||tag)) 암호화된 Redmine API 키
  redmine_login    TEXT,                     -- GET /users/current 가 반환한 login (표시/진단용)
  last_verified_at TEXT,                     -- ISO_LOCAL_DATE_TIME, 마지막 검증 성공 시각
  created_at       TEXT NOT NULL
);

-- 관리자 설정(미설정이면 기능 비활성)
INSERT INTO app_setting (key, value) VALUES ('redmine.enabled', '0');
INSERT INTO app_setting (key, value) VALUES ('redmine.base_url', '');
