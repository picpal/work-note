/* 템플릿 폼 클라이언트 선검증 — null이면 통과. 상한은 백엔드 TemplateLimits와 같은 값이고 서버가 최종. */
export const NAME_MAX = 50;
export const BODY_MAX = 100_000;

export function validateTemplateName(name: string): string | null {
  if (!name.trim()) return "템플릿 이름을 입력하세요.";
  if (name.trim().length > NAME_MAX) return `이름은 ${NAME_MAX}자 이하여야 합니다.`;
  return null;
}

export function validateTemplateBody(body: string): string | null {
  if (!body.trim()) return "템플릿 본문이 비어 있습니다.";
  if (body.length > BODY_MAX) return `본문은 ${BODY_MAX}자 이하여야 합니다.`;
  return null;
}
