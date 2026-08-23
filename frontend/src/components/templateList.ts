/* 템플릿 목록의 결정 로직 — 컴포넌트에서 분리해 vitest로 검증한다(프런트 테스트 관례). */
import type { ApiTemplate } from "../api/templates";

export interface TemplateGroups {
  system: ApiTemplate[];
  mine: ApiTemplate[];
}

/** 시스템/개인 2그룹으로 나누고 각 그룹을 이름 오름차순 정렬. 입력 배열은 변형하지 않는다. */
export function groupTemplates(items: ApiTemplate[]): TemplateGroups {
  const byName = (a: ApiTemplate, b: ApiTemplate) => a.name.localeCompare(b.name, "ko");
  return {
    system: items.filter((t) => t.system).sort(byName),
    mine: items.filter((t) => !t.system).sort(byName),
  };
}

/** 사용자 모달에서 수정·삭제 버튼을 노출할지 — 시스템 템플릿은 관리자 화면에서만 편집한다. */
export function canEdit(t: ApiTemplate): boolean {
  return !t.system;
}

/** 템플릿 본문을 커서 삽입용으로 앞뒤에 빈 줄(개행 두 개)로 감싼다.
 *
 *  개행 하나만으로는 블록을 못 끊는다 — GFM 표는 뒤에 빈 줄이 없으면 다음 줄을 계속 표의
 *  행으로 흡수하고, 목록도 빈 줄 없이 이어지는 줄을 같은 항목의 lazy continuation으로 삼는다.
 *  그래서 삽입 블록의 앞뒤 모두 완전한 빈 줄로 갈라야 커서 주변의 기존 내용(표·목록·문단
 *  무엇이든)에 흡수되지 않는다 — 커서가 줄 중간이라도 마찬가지다.
 *
 *  앞쪽은 손대지 않는다: 들여쓴 코드 블록·중첩 목록처럼 선행 들여쓰기가 구조인 본문이 있어
 *  앞을 trim하면 그 구조가 깨진다. 뒤쪽만 trimEnd — 본문이 개행으로 끝나든 아니든 결과의
 *  뒤쪽 빈 줄 수가 항상 같아지도록 정규화한다. trimEnd()는 JS String.trim()과 같은 공백
 *  범위(U+FEFF 포함)를 정규식/루프 없이 걷어내는 내장 메서드라 ReDoS 우려가 없다. */
export function wrapForInsert(body: string): string {
  return `\n\n${body.trimEnd()}\n\n`;
}
