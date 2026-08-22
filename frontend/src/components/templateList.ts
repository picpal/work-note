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
