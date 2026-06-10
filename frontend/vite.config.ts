import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

export default defineConfig({
  plugins: [react()],
  base: "./", // 폐쇄망 정적 서빙: 어느 경로에 놓여도 동작
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, "index.html"),
        login: resolve(__dirname, "login.html"),
        admin: resolve(__dirname, "admin.html"),
      },
    },
  },
  test: { environment: "node" },
});
