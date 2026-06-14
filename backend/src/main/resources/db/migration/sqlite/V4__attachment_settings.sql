-- V4__attachment_settings.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB)
CREATE TABLE attachment (
  id         TEXT PRIMARY KEY,
  node_id    TEXT NOT NULL REFERENCES node(id),
  filename   TEXT NOT NULL,
  ext        TEXT NOT NULL,
  mime       TEXT NOT NULL,
  size       INTEGER NOT NULL,
  rel_path   TEXT NOT NULL,
  created_by TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_attachment_node ON attachment(node_id);

CREATE TABLE app_setting (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
INSERT INTO app_setting (key, value) VALUES
  ('upload.allowed_ext', 'png,jpg,jpeg,gif,webp,pdf,docx,xlsx,pptx,txt,md,csv,zip'),
  ('upload.max_bytes', '26214400');
