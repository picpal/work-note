# e2e 구축 lessons

진행 중 발생한 오판단·오류와 교정 기록. 새 spec 작성 전에 반드시 훑을 것.
표기: **[겪음]** 실제로 틀려서 고친 것 / **[사전회피]** 조사 단계에서 발견해 미리 피한 함정.

## 환경·하네스

### 1. [겪음] pnpm 11은 package.json `"pnpm"` 필드를 읽지 않는다
- **증상**: `pnpm.onlyBuiltDependencies`를 package.json에 넣었는데 esbuild 빌드 스크립트가 계속 차단됨 (`ERR_PNPM_IGNORED_BUILDS`).
- **원인**: pnpm 11부터 설정의 위치가 `pnpm-workspace.yaml`로 이동. package.json의 `pnpm` 필드는 WARN과 함께 무시된다.
- **교정**: `frontend/pnpm-workspace.yaml`에 `onlyBuiltDependencies: [esbuild]` 작성.

### 2. [겪음] esbuild는 간접 의존성이라 `pnpm exec esbuild`로 검증 불가
- **증상**: 승인 후에도 `pnpm exec esbuild --version` → "Command not found"로 승인 실패처럼 보임.
- **원인**: `node_modules/.bin`에는 직접 의존성 바이너리만 노출된다. esbuild는 vite의 하위 의존성.
- **교정**: 승인 여부는 `pnpm build` 성공으로 판정한다. 중간 검증 스텝에 간접 의존성 바이너리를 쓰지 말 것.

### 3. [사전회피] vitest 기본 include가 `*.spec.ts`를 집어삼킨다
- e2e spec을 `.spec.ts`로 만들면 `pnpm test`(vitest)가 Playwright 파일을 실행하려다 죽는다.
- vite.config.ts에 `test.exclude: [...configDefaults.exclude, "e2e/**"]` 선반영.

### 4. [사전회피] e2e는 dev 서버가 아니라 "빌드된 jar"로 돌린다
- `pnpm dev`는 `VITE_STORAGE` 미설정 → localStorage 모드로 떠서 공유 링크·휴지통·로그아웃·첨부 UI가 아예 렌더되지 않는다 (`src/storage/index.ts`).
- `.env.production`이 적용되는 `pnpm build` 산출물 + bootJar(server 모드)만이 전체 화면을 노출한다.

### 5. [사전회피] 매 실행 DB 초기화가 필수
- 2FA 유예(7일, admin 최초 로그인 시 시작)·admin 부트스트랩 멱등성(사용자 0명일 때만)·감사 로그 누적이 전부 DB 상태에 물려 있다.
- webServer 스크립트(`scripts/start-server.sh`)가 기동 전 `.runtime/`을 통째로 지운다. server 모드는 DB 절대 경로 + 부모 디렉토리 700 요구 — `/tmp`(1777) 직접 사용 불가.

## 셀렉터·시나리오

### 6. [사전회피] 가입 모드에서 로그인 입력의 인덱스가 변한다
- 가입 전환 시 이름·이메일 필드가 중간에 삽입돼 nth() 기반 선택이 깨진다 (`LoginPage.tsx:192-207`).
- placeholder(`예: S2026-0142`)와 `type="password"`로 선택. password는 가입 모드에서 2개이므로 nth(0)/nth(1) 명시.

### 7. [사전회피] API 시딩 시 CSRF Origin 검증
- 변경 메서드(POST/PUT/PATCH/DELETE)는 Origin(없으면 Referer)이 서버 오리진과 일치해야 하고, 둘 다 없는데 세션 쿠키가 있으면 403 `invalid_origin` (`OriginValidator.java:52-74`).
- `request.newContext({ extraHTTPHeaders: { Origin: baseURL } })`를 표준으로 (helpers/session.ts).

### 8. [겪음] `browser.newContext()`는 프로젝트 `use`의 storageState를 상속한다
- **증상**: "비로그인 컨텍스트"를 만들려고 `browser.newContext()`를 그냥 불렀더니 admin 세션 쿠키를 물고 떠서 login.html이 즉시 index.html로 리다이렉트 (pending 승인 flow 2건 실패).
- **교정**: `browser.newContext({ storageState: EMPTY_STATE })` 명시. `test.use({ storageState: EMPTY_STATE })`는 describe 단위 — 한 테스트 안에서 admin+비로그인 컨텍스트를 섞을 땐 newContext 인자로 직접 넘기는 게 표준.

### 9. [사전회피] admin 화면 소소한 함정 모음
- Teams "멤버 관리"는 모달이 아니라 **우측 패널**(`.panel`) — `.modal-ov`로 기다리면 타임아웃.
- Users 추가 모달의 이름 필드는 placeholder가 없다 → `input.tinput` nth 선택.
- 시스템 역할 seed는 admin · operator(표시명 **"일반사용자"**, V10 rename) · visitor — "운영자"로 assert하면 깨짐.
- 가입 사번의 서버 규칙은 `\S+` 최대 64자 — uniq() 하이픈 사번 허용됨.

### 10. [사전회피] 이 레포에는 data-testid가 0개
- 한국어 버튼 텍스트·`title` 속성·짧은 클래스(`.auth-btn`, `.sb-fbtn`, `.pf-card` 등)가 유일한 훅.
- 같은 텍스트가 링크/버튼 양쪽에 있는 경우가 있다(로그인 폼의 "가입 신청" = 전환 링크, 가입 폼의 "가입 신청" = 제출 버튼) → 클래스(`.auth-link` vs `.auth-btn`)로 구분할 것.

### 11. [겪음·치명] chromium 테스트 안에서 `apiLogin`(전역 request.newContext) 금지 — 공유 admin 세션이 죽는다
- **증상**: 한참 뒤 테스트부터 세션 만료(401 → login.html 리다이렉트). 원인이 멀리 떨어져 있어 추적이 매우 어려움.
- **원인**: 전역 `request.newContext()`가 프로젝트 `use.storageState`(공유 admin 쿠키)를 상속 → apiLogin의 로그인 POST가 UI와 **같은 JSESSIONID**를 실어 보냄 → 백엔드 세션 고정 방어(`http.changeSessionId()`)가 그 세션 id를 회전 → storageState 파일의 쿠키가 그 시점부터 전 테스트에서 무효.
- **교정**: 테스트 내 admin API 시딩은 **`page.request`**(UI 세션 재사용, 변경 메서드엔 Origin 헤더 명시)로. `apiLogin`은 EMPTY_STATE 컨텍스트나 setup 프로젝트에서만 안전.
- **후속 조치**: 두 에이전트가 독립적으로 같은 함정에 빠짐 → apiLogin 본체에 `storageState: {cookies:[],origins:[]}`를 명시해 함정 자체를 제거했다. 교훈: 함정은 문서 경고가 아니라 **API 형태로 제거**하는 게 맞다.

### 12. [겪음] 상태별로 다른 섹션 라벨 — 첫 grep 매치로 단정하지 말 것
- SecurityTab의 "2단계 인증 등록"은 QR setup 단계 전용, 미등록 idle 상태는 "2단계 인증 (TOTP)" (SecurityTab.tsx:93 vs :125). 코드에서 라벨을 찾을 땐 렌더 분기 전체를 확인.

### 13. [겪음] 같은 문구 토스트가 겹쳐 strict mode 위반
- 토스트 수명 1.5s + 스로틀이 '직전 key'만 비교 → 삭제→복구→재삭제가 1.5s 안에 끝나면 "노트를 삭제했습니다"가 2개 동시 표시. 반복 액션의 토스트 assertion은 `.first()`로.

### 14. [사전회피] 메인 앱 시나리오 함정 모음
- **낙관적 UI + fire-and-forget**: 생성/이름변경/삭제/이동은 UI 반영 ≠ 서버 반영 — reload 검증 전 반드시 해당 POST/PATCH/DELETE를 `waitForResponse`로 대기.
- **fresh DB에선 이동 노출 경고가 절대 안 뜬다**(노출 델타 0) — 경고 경로는 `PUT /api/admin/nodes/{id}/public {"mode":"public"}`으로 public 플래그를 심어 재현.
- 루트 우클릭 생성은 "모두 접기" 후 마지막 행 아래 빈 좌표 클릭(행 위 우클릭은 노드 메뉴로 잡힘). 접힌 폴더에 '새 노트' 생성 시 rename 입력이 렌더 안 됨 → 폴더를 먼저 펼칠 것.
- 테스트 데이터에 이메일/전화번호 형태 문자열 금지 — PII 스캐너가 배너·알림 모달을 띄워 화면이 변한다. `uniq()`의 base36 접미사는 안전.
- 서버 트리 응답엔 `open` 필드가 없어 **로드 직후 폴더는 전부 접힘** — 폴더 하위 조작 전 폴더 행 클릭으로 펼칠 것.

### 15. [겪음] headless에서 ⌘/Ctrl+S가 앱 keydown 핸들러에 도달하지 않는다
- **증상**: 본문 입력 후 `press("ControlOrMeta+s")` 해도 flush PATCH가 안 나가 waitForResponse 타임아웃.
- **교정**: 같은 saveNow 경로인 플로팅 저장 버튼 `.doc-save.dirty` 클릭 + "저장됨" 전환 assert. 단축키 기반 검증은 headless에서 신뢰하지 말고 동일 코드 경로의 가시적 UI로 대체.

### 16. [겪음·오판] 시나리오 지시가 앱 스펙과 어긋났던 것 — 작성 전에 접근 모델부터 확인
- 지시서는 "비로그인 컨텍스트로 share.html 본문 렌더 확인"이었지만, 공유 열람(POST /api/share/{token}/view)은 **로그인 필수**(AuthFilter ALLOWLIST 밖 — 설계 결정). 올바른 기대는: 로그인 상태=본문 렌더 / 비로그인=401 "로그인이 필요한 링크" 카드.
- ShareModal은 URL/토큰을 DOM에 표시하지 않고 복사 버튼만 제공 → 토큰은 생성 POST(201) 응답 JSON에서 획득해 URL을 조립.
- 교훈: 시나리오를 지시/작성하기 전에 해당 기능의 **인증·접근 모델을 코드에서 먼저 확인**할 것. "read-only 공개 링크"라는 통념으로 기대를 쓰면 틀린다.

### 17. [겪음] 로그아웃 테스트가 공유 storageState 세션을 죽인다 — "단독 그린, 합주 레드"
- **증상**: 그룹별 실행은 전부 그린인데 전체 합주에서 12개 실패. 실패 파일이 전부 알파벳 순서상 login.spec **이후**(profile/share/tree), 이전 파일(admin/·attachments·editor)은 전부 통과 — 이 비대칭이 결정적 단서였다.
- **원인**: 로그아웃 테스트가 공유 storageState의 JSESSIONID로 로그아웃 → 서버가 그 세션을 무효화 → 이후 모든 spec이 401 → login.html 리다이렉트. 단독 실행에선 로그아웃이 사실상 마지막이라 안 잡힌다.
- **교정**: **공유 세션을 파괴하는 시나리오(로그아웃·비밀번호 변경·세션 무효화·2FA 강제)는 반드시 EMPTY_STATE 전용 컨텍스트에서 자체 로그인 후 수행**. #11과 같은 뿌리(공유 세션은 불변 자원으로 취급) — 스위트 전역 규칙으로 승격.

### 18. [겪음·중대] 설정 파일을 "신규 생성"하기 전에 기존 파일 존재를 확인하라
- **사고**: `pnpm-workspace.yaml`을 새 파일로 알고 Write로 생성 → 실제로는 기존 파일이 있었고, 보안 감사가 넣은 **`overrides: dompurify >=3.4.13`(XSS 방어 플로어)과 감사 근거 주석을 통째로 날렸다**. git status에서 `??`가 아니라 `M`으로 잡힌 걸 보고서야 발견, diff로 원본을 복원·병합했다.
- **교훈**: 도구/하네스 설정 파일(pnpm-workspace.yaml, .npmrc, tsconfig 등)은 만들기 전에 반드시 존재 확인 + 존재하면 읽고 병합. 커밋 전 `git status`에서 신규로 기대한 파일이 `M`으로 나오면 즉시 diff 확인. (부수 발견: 이 레포엔 이미 esbuild 빌드 승인이 있었다 — 처음의 ERR_PNPM_IGNORED_BUILDS는 `packages:` 키 부재와 얽힌 것으로 추정되며, 원인 단정 없이 두 요소를 모두 보존했다.)
