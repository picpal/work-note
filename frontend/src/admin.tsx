import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/admin.css";
import { setOn401 } from "./api/http";
import { AdminApp } from "./admin/AdminApp";

// 401 전역 핸들러 — 모듈 최상위 설치(StrictMode 이중 마운트 race 회피). admin 앱은 항상 HTTP — 조건 없음.
setOn401(() => { location.href = "login.html"; });
createRoot(document.getElementById("root")!).render(<AdminApp />);
