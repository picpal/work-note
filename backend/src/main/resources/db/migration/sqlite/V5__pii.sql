-- V5__pii.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB)
ALTER TABLE node ADD COLUMN updated_by TEXT;

CREATE TABLE pii_flag (
  node_id         TEXT PRIMARY KEY REFERENCES node(id),
  status          TEXT NOT NULL CHECK (status IN ('suspected','requested','exempted','rejected')),
  types           TEXT NOT NULL,
  detected_at     TEXT NOT NULL,
  requested_by    TEXT, requested_at TEXT, request_reason  TEXT,
  decided_by      TEXT, decided_at   TEXT, decision_reason TEXT
);

CREATE TABLE pii_notice (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id   TEXT NOT NULL REFERENCES node(id),
  recipient TEXT NOT NULL,
  kind      TEXT NOT NULL CHECK (kind IN ('flagged','approved','rejected')),
  message   TEXT,
  sent_by   TEXT NOT NULL, sent_at TEXT NOT NULL,
  ack_at    TEXT
);
CREATE INDEX idx_pii_notice_recipient ON pii_notice(recipient, ack_at);
