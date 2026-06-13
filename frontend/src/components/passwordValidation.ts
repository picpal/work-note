/* 비밀번호 변경 폼 클라이언트 선검증 — null이면 통과, 아니면 에러 메시지. 서버(AuthService)도 동일 정책 재검증. */
export function validatePasswordChange(cur: string, next: string, confirm: string): string | null {
  if (!cur || !next || !confirm) return "모든 비밀번호 항목을 입력하세요.";
  if (next.length < 10) return "새 비밀번호는 10자 이상이어야 합니다.";
  if (next !== confirm) return "새 비밀번호가 일치하지 않습니다.";
  if (next === cur) return "현재 비밀번호와 다른 비밀번호를 사용하세요.";
  return null;
}
