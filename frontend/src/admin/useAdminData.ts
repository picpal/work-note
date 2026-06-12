/* 관리자 앱 공통 데이터 컨텍스트 — AdminApp이 로드한 me/users/roles/teams를 스크린에 공급. */
import React from "react";
import type { Me } from "../api/auth";
import type { ApiRole, ApiTeam, ApiUser } from "./api";

export interface AdminData {
  me: Me | null;
  users: ApiUser[];
  roles: ApiRole[];
  teams: ApiTeam[];
  reload: () => Promise<void>;
  toast: (msg: string, icon?: string) => void;
}

export const AdminDataContext = React.createContext<AdminData | null>(null);

export function useAdminData(): AdminData {
  const ctx = React.useContext(AdminDataContext);
  if (!ctx) throw new Error("AdminDataContext가 없습니다 — AdminApp 하위에서만 사용");
  return ctx;
}
