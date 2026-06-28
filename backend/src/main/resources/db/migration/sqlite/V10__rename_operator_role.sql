-- 운영자 → 일반사용자 라벨 변경. id는 'operator' 그대로(user.role_id 참조 유지) — 표시명만 정리.
-- 가입 기본 역할은 visitor(읽기전용) 유지: AuthService.signup 미변경.
UPDATE role SET name = '일반사용자' WHERE id = 'operator';
