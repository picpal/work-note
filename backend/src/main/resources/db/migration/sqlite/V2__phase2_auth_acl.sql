-- V2__phase2_auth_acl.sql  (phase 2 권한 스키마 — node/tag는 V1 그대로, ANSI 지향)
-- 'user'·'grant'는 Oracle/PG 예약어 → app_user / grant_type 사용
CREATE TABLE role (
  id     TEXT PRIMARY KEY,
  name   TEXT NOT NULL,
  system INTEGER NOT NULL DEFAULT 0,
  caps   TEXT NOT NULL               -- JSON 배열 문자열: res.*/admin.* 집합
);

CREATE TABLE app_user (
  id         TEXT PRIMARY KEY,
  emp        TEXT NOT NULL UNIQUE,   -- 사번 = 로그인 식별자
  email      TEXT,
  name       TEXT NOT NULL,
  role_id    TEXT NOT NULL REFERENCES role(id),
  status     TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('pending','active','disabled')),
  last_login TEXT
);

-- 사용자별 salt 분리 테이블 (요구사항: salt는 사용자별 맵핑 테이블로 별도 관리)
CREATE TABLE user_credential (
  user_id       TEXT PRIMARY KEY REFERENCES app_user(id),
  salt          TEXT NOT NULL,       -- Base64(16바이트 SecureRandom)
  password_hash TEXT NOT NULL        -- Base64(PBKDF2-HMAC-SHA256, 120000회, 256bit)
);

CREATE TABLE team (
  id   TEXT PRIMARY KEY,
  name TEXT NOT NULL
);
CREATE TABLE team_member (
  team_id TEXT NOT NULL REFERENCES team(id),
  user_id TEXT NOT NULL REFERENCES app_user(id),
  PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_member_user ON team_member(user_id);  -- 권한 해석: 사용자→소속 팀(teamsOf) 조회

-- 팀 스페이스(1급): 최상위 폴더 ↔ 소유 팀. 관리자 API(다음 계획)에서 사용 — 스키마는 한 번에
CREATE TABLE space (
  node_id TEXT PRIMARY KEY REFERENCES node(id),
  team_id TEXT REFERENCES team(id)   -- NULL = 공용(소유 팀 없음)
);

CREATE TABLE acl (
  principal_type TEXT NOT NULL CHECK (principal_type IN ('user','team','all')),
  principal_id   TEXT NOT NULL,      -- @all은 센티넬 '@all'
  node_id        TEXT NOT NULL REFERENCES node(id),
  grant_type     TEXT NOT NULL CHECK (grant_type IN ('read','edit','deny')),
  PRIMARY KEY (principal_type, principal_id, node_id)
);
CREATE INDEX idx_acl_node ON acl(node_id);

CREATE TABLE public_flag (
  node_id TEXT PRIMARY KEY REFERENCES node(id),
  mode    TEXT NOT NULL CHECK (mode IN ('public','exclude'))
);

-- AUDIT은 Oracle 완전 예약어 → audit_log 사용 (app_user/grant_type와 동일 규칙)
CREATE TABLE audit_log (
  id     INTEGER PRIMARY KEY,        -- SQLite rowid 별칭 = 자동 증가 (AUTOINCREMENT 불필요)
  at     TEXT NOT NULL,
  who    TEXT NOT NULL,
  act    TEXT NOT NULL,
  target TEXT,
  ip     TEXT
);
CREATE INDEX idx_audit_log_at ON audit_log(at);

INSERT INTO role (id, name, system, caps) VALUES
 ('admin',    '관리자', 1, '["admin.users","admin.permissions","admin.roles","admin.security","admin.audit","res.read","res.edit","res.create","res.delete","res.export","res.share"]'),
 ('operator', '운영자', 1, '["res.read","res.edit","res.create","res.delete","res.export","res.share"]'),
 ('visitor',  '방문자', 1, '["res.read"]');
