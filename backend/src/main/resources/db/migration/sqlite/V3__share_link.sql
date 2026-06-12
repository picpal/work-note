-- V3__share_link.sql  (스펙 §6 공유 링크 — deny를 넘는 유일한 read 예외, 열거 가능)
CREATE TABLE share_link (
  id         TEXT PRIMARY KEY,
  token      TEXT NOT NULL UNIQUE,            -- SecureRandom 32B base64url — 원문 저장(폐쇄망, 결정 S1)
  node_id    TEXT NOT NULL REFERENCES node(id),
  created_by TEXT NOT NULL,                   -- 사번(emp), local 모드는 'local'
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,                   -- 기본 7일 (스펙 §6)
  max_views  INTEGER,                         -- NULL = 무제한
  view_count INTEGER NOT NULL DEFAULT 0,
  pin_emps   TEXT,                            -- NULL = 전 직원, 값 = JSON 배열(사번)
  revoked_at TEXT                             -- NULL = 미취소
);
CREATE INDEX idx_share_link_node ON share_link(node_id);
