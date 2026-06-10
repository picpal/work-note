import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/app.css";
import "./styles/settings.css";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(<App />);
