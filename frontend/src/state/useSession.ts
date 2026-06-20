/* useSession — http 모드 전용 세션 부트스트랩. local 스토리지 모드는 me=null 고정(인증 개념 없음). */
import { useState, useEffect } from "react";
import { AuthApi } from "../api/auth";
import type { Me } from "../api/auth";
import { storageMode } from "../storage";

export function useSession(): { me: Me | null; setMe: (m: Me | null) => void; meReady: boolean; isAdmin: boolean; logout: () => void } {
  const [me, setMe] = useState<Me | null>(null);
  // meReady: http 모드에서 me fetch가 settle(성공/실패) 됐는지. local 모드는 항상 true(인증 개념 없음).
  // "아직 로딩 중"(meReady=false)과 "fetch 실패로 me=null 고정"(meReady=true, me=null)을 구분 —
  //  → 강제 게이트 평가 전 flash-of-content 방지(아직 로딩 중이면 App이 렌더를 보류).
  const [meReady, setMeReady] = useState(storageMode !== "http");

  useEffect(() => {
    if (storageMode !== "http") return;
    // 성공/실패 모두 settle 표시 — 실패(서버 다운/401)는 me=null 고정, 로딩 종료로 간주.
    AuthApi.me()
      .then(setMe)
      .catch(() => { /* 401은 전역 on401이 처리, 그 외(서버 다운)는 무세션 표시 */ })
      .finally(() => setMeReady(true));
  }, []);

  const logout = () => {
    AuthApi.logout().finally(() => { location.href = "login.html"; });
  };

  return { me, setMe, meReady, isAdmin: me?.caps.includes("admin.users") ?? false, logout };
}
