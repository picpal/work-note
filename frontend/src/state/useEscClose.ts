import { useEffect, useRef } from "react";

/** 모달/팝업이 열려 있는 동안 ESC로 닫기.
   - 조건부 렌더되는 모달은 마운트=열림이라 active 생략(기본 true).
   - 항상 마운트되는 모달(내부 상태로 표시 제어)은 표시 여부를 active로 넘겨 닫혀 있을 때 핸들러 미부착.
   ref로 최신 onClose를 캡처해 매 렌더 재구독 없이 mount당 1회만 등록. */
export function useEscClose(onClose: () => void, active = true) {
  const ref = useRef(onClose);
  ref.current = onClose;
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.preventDefault(); ref.current(); }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [active]);
}
