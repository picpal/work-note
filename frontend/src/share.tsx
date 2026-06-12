import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import { SharePage } from "./share/SharePage";

// 401 핸들러(setOn401) 미설치 — 리다이렉트하면 로그인 후 링크로 복귀 불가(결정 S12).
// 401은 SharePage가 unauthorized 상태 화면(로그인 안내)으로 처리한다.
createRoot(document.getElementById("root")!).render(<SharePage />);
