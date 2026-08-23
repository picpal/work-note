-- V13__note_template.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB)
-- 노트 템플릿. owner_id IS NULL = 시스템 템플릿(관리자만 편집), 그 외 = 개인 템플릿.
-- app_user FK를 걸지 않는다: local 모드는 app_user 행이 없어 소유자 키로 'local' 상수를 쓴다.
CREATE TABLE note_template (
  id         TEXT PRIMARY KEY,
  owner_id   TEXT,
  name       TEXT NOT NULL,
  body       TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_note_template_owner ON note_template(owner_id);

INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at) VALUES
  ('tpl-meeting', NULL, '회의록', '## 개요
- 일시:
- 장소:
- 참석자:

## 안건
1.

## 결정 사항
-

## 액션 아이템
| 담당 | 할 일 | 기한 |
| --- | --- | --- |
|  |  |  |
', '2026-08-18T00:00:00', '2026-08-18T00:00:00');

INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at) VALUES
  ('tpl-weekly', NULL, '주간보고', '## 이번 주 진행
-

## 다음 주 계획
-

## 이슈·리스크
-
', '2026-08-18T00:00:00', '2026-08-18T00:00:00');

INSERT INTO note_template (id, owner_id, name, body, created_at, updated_at) VALUES
  ('tpl-incident', NULL, '장애보고', '## 요약
- 발생 시각:
- 영향 범위:
- 현재 상태:

## 타임라인
| 시각 | 내용 |
| --- | --- |
|  |  |

## 원인

## 조치

## 재발 방지
', '2026-08-18T00:00:00', '2026-08-18T00:00:00');
