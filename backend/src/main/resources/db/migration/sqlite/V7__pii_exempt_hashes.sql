-- V7__pii_exempt_hashes.sql
-- 승인된 PII 값 해시의 누적 집합(CSV). 관리자가 허용할 때마다 현재 값 해시를 추가.
-- 현재 탐지 값이 이 집합에 있으면 예외 재적용 → 승인했던 값으로 돌아오면 다시 예외 처리됨.
ALTER TABLE pii_flag ADD COLUMN exempt_hashes TEXT;
