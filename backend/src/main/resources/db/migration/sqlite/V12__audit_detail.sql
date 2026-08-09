-- 감사 델타(M-1) — target은 사람이 읽는 라벨로 이미 감사 화면·월간 리포트가 소비하므로
-- JSON을 우겨넣지 않고 별도 컬럼으로 분리. nullable ADD COLUMN (V9의 totp_grace_start와 같은 저위험 패턴).
ALTER TABLE audit_log ADD COLUMN detail TEXT;
