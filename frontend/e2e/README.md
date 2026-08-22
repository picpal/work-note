# work-note e2e (Playwright)

화면별 필수 기능 회귀 테스트. 빌드된 jar(server 모드)를 매 실행 새 DB로 띄워서 돈다.

## 실행

```bash
cd frontend
pnpm e2e:build   # dist 빌드 + bootJar (코드 변경 후 1회)
pnpm e2e         # 전체 실행 (서버 기동·DB 초기화 자동)
pnpm e2e e2e/specs/login.spec.ts   # 특정 화면만
pnpm e2e:ui      # UI 모드
```

- 포트: 8331 (`E2E_PORT`로 변경). dev 백엔드 8080과 충돌하지 않는다.
- 계정: `admin` / `e2e-admin-pass-1234` (scripts/start-server.sh에서 부트스트랩).
- DB·업로드는 `e2e/.runtime/`에 만들고 실행 시마다 초기화. 산출물은 `playwright-report/`.

## 구조

```
e2e/
  scripts/start-server.sh   # webServer 커맨드: DB 리셋 + jar 기동 (포트별 .runtime-<port>/)
  setup/auth.setup.ts       # admin UI 로그인 → storageState 저장
  helpers/session.ts        # 계정 상수, API 시딩 컨텍스트(Origin 헤더 필수)
  fixtures/                 # 첨부 테스트용 파일 (txt/png/exe)
  specs/                    # 화면별 spec — 16파일 54테스트
    login.spec.ts           #   로그인·가입·로그아웃 (파일럿)
    editor / tree / profile / attachments / share.spec.ts   # index·share 화면
    admin/*.spec.ts         #   관리자 12스크린 (dashboard~redmine)
  lessons.md                # 구축 중 오류·교정 기록 — spec 추가 전 필독
  e2e-test-skill.md         # 스킬화 대비 노트
```

## 관례

- 모든 spec은 admin 세션으로 시작한다. 비로그인 시나리오는 `test.use({ storageState: EMPTY_STATE })`.
- 직렬 실행(workers: 1) — 단일 DB 공유. 테스트가 만드는 데이터는 `uniq()` 접미사로 유일하게.
- 셀렉터: 한국어 버튼 텍스트·title 속성·클래스 조합 (레포에 data-testid 없음, lessons.md #8).
