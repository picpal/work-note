import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/settings.css";
import { setOn401 } from "./api/http";
import { storageMode } from "./storage";
import { App } from "./App";

// 401 전역 핸들러 — 모듈 최상위 설치 (컴포넌트 effect가 아님: StrictMode 이중 마운트/cleanup race 회피)
if (storageMode === "http") {
  setOn401(() => { location.href = "login.html"; });
}

createRoot(document.getElementById("root")!).render(<App />);
