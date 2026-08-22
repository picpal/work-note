import { defineConfig, devices } from "@playwright/test";
import { ADMIN_STATE, E2E_PORT } from "./e2e/helpers/session";

/**
 * work-note 화면별 e2e.
 *
 * 실행 경로: pnpm e2e:build (dist + bootJar) → pnpm e2e
 * - dev 서버(vite)가 아니라 "빌드된 jar"를 띄운다. dev 모드는 VITE_STORAGE 미설정 시
 *   localStorage 모드로 떠서 인증·공유·휴지통 UI가 아예 렌더되지 않기 때문.
 * - webServer 스크립트가 매 실행 DB를 초기화한다 (admin 사번 "admin" / 비번은 스크립트 참조).
 */
const PORT = E2E_PORT;
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: "./e2e",
  // 포트별 격리 — 병렬 실행이 서로의 산출물을 덮어쓰지 않도록
  outputDir: `./test-results/p${PORT}`,
  // 단일 백엔드 + 단일 DB를 모든 테스트가 공유한다 — 상태 간섭을 피하려 직렬 실행
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: `playwright-report/p${PORT}` }]],
  timeout: 30_000,
  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "ko-KR",
    timezoneId: "Asia/Seoul",
  },
  webServer: {
    command: "bash e2e/scripts/start-server.sh",
    url: `${BASE_URL}/api/health`,
    reuseExistingServer: false,
    timeout: 90_000,
    stdout: "pipe",
    stderr: "pipe",
    env: { E2E_PORT: String(PORT) },
  },
  projects: [
    // 1) admin 로그인 → storageState 저장 (모든 spec의 선행 의존)
    { name: "setup", testMatch: /e2e\/setup\/.*\.setup\.ts/ },
    // 2) 본 스위트 — 기본적으로 admin 세션을 물고 시작한다.
    //    비로그인 시나리오는 spec에서 test.use({ storageState: EMPTY_STATE })로 덮어쓴다.
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: ADMIN_STATE },
      dependencies: ["setup"],
      testMatch: /e2e\/specs\/.*\.spec\.ts/,
    },
  ],
});
