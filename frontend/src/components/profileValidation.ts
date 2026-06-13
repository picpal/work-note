/* 프로필 정보 폼 클라이언트 선검증 — null이면 통과, 아니면 에러 메시지. 서버(@NotBlank @Size)가 최종. */
export function validateProfile(name: string): string | null {
  if (!name.trim()) return "이름을 입력하세요.";
  if (name.trim().length > 64) return "이름은 64자 이하여야 합니다.";
  return null;
}
