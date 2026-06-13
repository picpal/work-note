/* useSession — http 모드 전용 세션 부트스트랩. local 스토리지 모드는 me=null 고정(인증 개념 없음). */
import { useState, useEffect } from "react";
import { AuthApi } from "../api/auth";
import type { Me } from "../api/auth";
import { storageMode } from "../storage";

export function useSession(): { me: Me | null; setMe: (m: Me | null) => void; isAdmin: boolean; logout: () => void } {
  const [me, setMe] = useState<Me | null>(null);

  useEffect(() => {
    if (storageMode !== "http") return;
    AuthApi.me().then(setMe).catch(() => { /* 401은 전역 on401이 처리, 그 외(서버 다운)는 무세션 표시 */ });
  }, []);

  const logout = () => {
    AuthApi.logout().finally(() => { location.href = "login.html"; });
  };

  return { me, setMe, isAdmin: me?.caps.includes("admin.users") ?? false, logout };
}
