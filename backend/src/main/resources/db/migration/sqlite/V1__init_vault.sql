-- V1__init_vault.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB, 스크립트는 db/migration/oracle에 별도 작성)
CREATE TABLE node (
  id         TEXT PRIMARY KEY,
  parent_id  TEXT REFERENCES node(id),
  type       TEXT NOT NULL CHECK (type IN ('folder','note')),
  name       TEXT NOT NULL,
  position   INTEGER NOT NULL,
  content    TEXT,
  updated_at TEXT,
  deleted_at TEXT,
  deleted_by TEXT
);
CREATE INDEX idx_node_parent ON node(parent_id);
CREATE INDEX idx_node_deleted ON node(deleted_at);

CREATE TABLE tag (
  node_id TEXT NOT NULL REFERENCES node(id),
  tag     TEXT NOT NULL,
  PRIMARY KEY (node_id, tag)
);
