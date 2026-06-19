/* 역할 카드 액션 버튼의 모드 판정.
   시스템 역할(admin/operator/visitor)은 변경 불가지만, 이전엔 편집 버튼을 disabled로 둬서
   '죽은 클릭'(먹통)이 됐다. 이제 시스템 역할은 읽기전용 '보기' 모달로 권한을 확인하게 하고,
   커스텀(비시스템) 역할만 '편집'이 된다. 판정은 system 플래그 하나에 종속. */
export type RoleMode = "view" | "edit";

export function roleMode(role: { system: boolean }): RoleMode {
  return role.system ? "view" : "edit";
}

export function roleActionLabel(mode: RoleMode): string {
  return mode === "view" ? "보기" : "편집";
}

export function roleActionIcon(mode: RoleMode): string {
  return mode === "view" ? "eye" : "edit";
}
