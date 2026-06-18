-- V8__node_created_at.sql — 노드 생성일(정렬용). 폴더·노트 공통.
-- SQLite ALTER TABLE ADD COLUMN은 비상수 DEFAULT(CURRENT_TIMESTAMP 등)를 허용하지 않으므로
-- 컬럼만 추가한 뒤 backfill. 신규 행은 NodeMapper.insert가 채운다(NodeRow는 무변경).
ALTER TABLE node ADD COLUMN created_at TEXT;

-- 기존 행 backfill: 최선의 근사치 = updated_at(없으면 현재 로컬시각). ISO_LOCAL_DATE_TIME 포맷(T 구분자)로 통일.
UPDATE node
   SET created_at = COALESCE(updated_at, strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
 WHERE created_at IS NULL;
