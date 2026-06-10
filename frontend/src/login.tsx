import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/login.css";
import { LoginPage } from "./login/LoginPage";
createRoot(document.getElementById("root")!).render(<LoginPage />);
