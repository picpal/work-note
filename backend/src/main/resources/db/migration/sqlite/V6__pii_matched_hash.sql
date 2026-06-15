-- V6__pii_matched_hash.sql
-- 값 기준 예외: 탐지된 PII 원문 스팬의 해시(SHA-256 hex). 평문 PII는 저장하지 않음.
-- exempted 상태에서 재평가 시 해시가 바뀌면(값 변경) 의심으로 복귀시키기 위함.
ALTER TABLE pii_flag ADD COLUMN matched_hash TEXT;
