import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/admin.css";
import { AdminApp } from "./admin/AdminApp";
createRoot(document.getElementById("root")!).render(<AdminApp />);
