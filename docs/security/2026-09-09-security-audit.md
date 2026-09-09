# 2026-09-09 보안 점검 — secscan standard

- 도구: [`workspace/security-checker`](../../../security-checker) `secscan scan --profile standard`
  (Trivy + osv 합의 SCA · OWASP dep-scan 도달성 · Gitleaks · Semgrep CE + 커스텀 룰)
- 대상: `work-note` 전체 (커밋 `657b6a7` 시점)
- 스캐너 상태: gitleaks ok(2.4s) · semgrep ok(7.1s) · trivy ok(8.7s) — **부분 실패 없음**
- 원본 산출물: `security-checker/out/work-note/{report.md,findings.json,findings.sarif}`

## 요약

총 **15건** (심각 3 · 위험 2 · 보통 7 · 미상 3). 기본 제외 23건.

| 분류 | 건수 | 판정 |
|---|---|---|
| SCA — tomcat-embed-core 10.1.55 | 3 (심각) | **조치** — 버전 상향 |
| SCA — log4j-api 2.24.3 | 1 (보통) | **조치** — 버전 상향 |
| Secret — QA 문서 비밀번호 리터럴 | 1 (위험) | **조치** — 환경변수화 (실 자산 아님) |
| SAST — pnpm 공급망 정책 | 3 (검토후보) | **조치 검토** — 아래 §4 |
| SAST — 하드코드 자격증명(커스텀 룰) | 1 (위험) | **오탐** |
| SAST — XSS / postMessage (프로토타입) | 4 (보통) | **오탐 · 범위 밖** |
| SAST — dangerouslySetInnerHTML (실코드) | 2 (보통) | **오탐** |

> 컴플라이언스 표기(KISA·PCI)는 finding 의 CWE 에서 파생한 **분류**이지 합격/불합격 판정이 아니다.

## 1. SCA — tomcat-embed-core 10.1.55 (심각 3건)

| CVE | CVSS | 내용 |
|---|---|---|
| CVE-2026-65182 | 9.1 | security constraint 우회 (CWE-284/863) |
| CVE-2026-65905 | 9.8 | DIGEST authenticator 재생 공격으로 인증 우회 (CWE-294) |
| CVE-2026-68525 | 9.1 | FORM 인증 우회로 비인가 리소스 접근 (CWE-863) |

**도달성 판정을 뒤집었다.** dep-scan 은 셋 다 `unreachable`(evidence 없음)로 강등했지만,
`tomcat-embed-core` 는 이 앱의 **내장 HTTP 서버 그 자체**다. 애플리케이션 코드에서 직접
호출하지 않는다는 사실이 "실행되지 않는다"는 뜻이 아니다 — 정적 도달성이 컨테이너·DI·
애노테이션 라우팅을 놓치는 전형적인 사각지대(보고서 자체의 경고 문구와 동일)라, 강등 근거로
쓰지 않았다.

**다만 세 CVE 모두 이 앱에서 직접 악용 가능한 형태는 아니다.** work-note 는 Tomcat 의
`security-constraint`(web.xml)·DIGEST·FORM 인증을 쓰지 않고 자체 `AuthFilter` + 세션으로
인가를 처리한다. 그래도 "안 쓰니 괜찮다"에 기대지 않고 버전을 올리는 쪽을 택했다 — 비용이
핀 두 줄이고, 그 전제(향후에도 컨테이너 인증을 안 쓴다)를 코드가 강제하지 못하기 때문이다.

**함정: 수정버전 `10.1.58` 은 Maven Central 에 존재하지 않는다**(릴리스 스킵, 404).
trivy 가 알려준 fixed_versions 를 그대로 쓰면 빌드가 깨진다. 10.1.x 최신인 **10.1.59** 로 올렸다.

## 2. SCA — log4j-api 2.24.3 (보통 1건)

CVE-2026-49844 (CWE-116, 부동소수점 값 인코딩 오류로 JSON 로그 출력이 깨짐).
Spring Boot 의 `log4j-to-slf4j` 브리지를 통해 들어오는 API 전용 아티팩트라 이 앱은
log4j 를 로깅 구현으로 쓰지 않는다(실피해 없음). 2.25.5 로 상향 — 비용이 낮고 스캔 노이즈를 없앤다.

## 3. Secret — `docs/qa-e2e-scenarios.md`

gitleaks `generic-api-key`. **실 자산이 아니다** — `http://localhost:8080` 대상 QA 시딩 절차의
픽스처 값이라 회수·교체 대상이 아니고, 히스토리 재작성도 하지 않았다. 다만 문서가 평문
비밀번호를 박아 두는 관행을 가르치고 스캔 노이즈를 매번 만들기 때문에 환경변수 참조로 바꿨다.

## 4. SAST 검토후보 — pnpm 공급망 정책 3건

`frontend/pnpm-workspace.yaml` 에 대해 semgrep 이 CWE-829(신뢰할 수 없는 제어 영역의 코드 포함)로
3건을 올렸다: 최소 배포 경과시간(minimum release age) 미설정 · trust policy · exotic 하위 의존성 차단.
조치 내용과 판단 근거는 §7 조치표 참조.

## 5. 오탐 — `BreakGlassFile.java:88` (커스텀 룰 hardcoded-credential)

```java
private static final String EMP = "emp";
private static final String PASSWORD = "password";
private static final Set<String> KNOWN_KEYS = Set.of(EMP, PASSWORD);
```

센티넬 파일의 **키 이름**을 파싱하기 위한 상수다. 값이 아니라 키다 —
`password=<값>` 형태의 라인에서 왼쪽을 식별하는 문자열이라 자격증명이 아니다.
(secscan 커스텀 룰은 `이름 = "리터럴"` 패턴을 잡는데, 여기서는 이름과 값이 우연히 같다.)

## 6. 오탐 — XSS / postMessage

| 위치 | 근거 |
|---|---|
| `docs/design-handoff/prototype/icons.jsx:76` | 배포물에 포함되지 않는 디자인 핸드오프 산출물. `dangerouslySetInnerHTML` 의 입력이 파일 내 상수 SVG path 테이블(`P[name]`)이라 외부 입력이 닿지 않는다 |
| `docs/design-handoff/prototype/tweaks-panel.jsx:182,236,242` | 동일 — 프로토타입 전용 트윅 패널의 `postMessage(..., '*')`. `vite.config.ts`·`build.gradle` 어디에서도 참조하지 않아 빌드·jar 에 들어가지 않는다 |
| `frontend/src/components/RedmineImportPanel.tsx:238,239` | 삽입값이 `renderMarkdown()` 반환값이고, 그 함수의 마지막 문장이 `return DOMPurify.sanitize(tpl.innerHTML)` 이다(`frontend/src/lib/markdown.ts:183`). mermaid 경로도 `securityLevel:"strict"` + 반환 SVG 재-sanitize 로 이중 방어 |

프로토타입 4건은 억제(suppression) 후보이나 **자동 억제하지 않았다** — 억제는 사람이 확정한다.
스캔 때마다 다시 보이는 것이 비용이라면 `docs/design-handoff/` 를 스캔 제외로 확정하는 편이 낫다.

## 7. 조치표

| # | 대상 | 조치 | 검증 |
|---|---|---|---|
| 1 | tomcat-embed-core 10.1.55 (심각 3) | `ext['tomcat.version'] = '10.1.59'` | `dependencyInsight`: `10.1.55 -> 10.1.59`, jar 번들 `tomcat-embed-{core,el,websocket}-10.1.59.jar` |
| 2 | log4j-api 2.24.3 (보통 1) | `ext['log4j2.version'] = '2.25.5'` | `log4j-api:2.25.5 (selected by rule)`, `log4j-to-slf4j` 동반 상향 |
| 3 | QA 문서 비밀번호 리터럴 (위험 1) | `${WN_QA_*_PW:?}` 환경변수 참조로 전환 (2개 문서) | gitleaks `docs/` **1→0**, 추적 소스 신규 탐지 0 |
| 4 | pnpm 공급망 정책 (검토후보 3) | `minimumReleaseAge` · `trustPolicy` · `blockExoticSubdeps` | semgrep 해당 룰 **3→0**, 락파일 불변, 430 tests·build 통과 |
| 5 | 오탐 8건 | 억제하지 않고 근거만 문서화 (§5·§6) | — |

### 7.1 백엔드 의존성 (조치 1·2)

수동 핀은 이 저장소에서 **최후 수단**이다(BOM이 따라잡으면 조용한 다운그레이드가 되므로).
Boot 3.5.x 최신 패치가 이미 3.5.16이라 **BOM 상향으로는 해소되지 않아** 예외적으로 핀을 늘렸다.
Boot 을 올릴 때 세 핀(jackson·tomcat·log4j2)을 반드시 재검토해야 한다 — 2026-08-07 감사의
M-7(logback 핀 1.5.33이 BOM 1.5.34를 막던 사고)이 그대로 재현될 수 있는 자리다.

`log4j2.version` 은 log4j 계열 전체를 움직인다(여기서는 `log4j-to-slf4j` 동반 상향).
두 아티팩트 모두 2.25.5 존재를 사전 확인했다.

검증 범위: 의존성 해석 + `./gradlew test` **814 tests / 실패 0**(skip 2는 기존) + `bootJar` 성공.
**tomcat 55→59 4단계 점프에 대한 실기동 스모크는 하지 않았다** — server 모드 기동 QA는 별도로 한 번 태우는 것을 권한다.

### 7.2 QA 문서 (조치 3)

값 자체는 회수 대상이 아니라(로컬 픽스처) **문서 패턴 교정**으로 처리했다. e2e 스위트는
다른 값(`e2e-admin-pass-1234`, `frontend/e2e/helpers/session.ts`)을 쓰므로 문서 변경이 테스트를 깨지 않는다.

절차가 여전히 동작하는지는 **문서의 bash 블록을 실제로 실행해** 확인했다 — `curl` 을 스텁으로
바꿔 전송 페이로드를 찍었고, 작은따옴표→큰따옴표 전환 후에도 바이트가 원본과 **동일**했다
(한글 `운영자`/`대기자` 보존, `json.load` 파싱 통과, `bash -n` 통과).
변수 미설정 시 `:?` 가 즉시 실패시켜 빈 비밀번호로 조용히 기동하는 사고를 막는다.

git 히스토리에는 과거 커밋(`2d81a6b`, 2026-06-14)에 같은 값이 남아 있다. 실 자산이 아니므로
히스토리 재작성은 하지 않았다.

### 7.3 pnpm 공급망 (조치 4)

세 설정 모두 pnpm 11.5.3에서 지원됨을 확인하고 넣었다. **pnpm 은 모르는 workspace 키를 경고
없이 무시**하므로(주입 테스트로 확인), 낮은 버전에서는 이 하드닝 전체가 무성음 no-op 이 된다 —
그래서 `package.json` 에 `packageManager: "pnpm@11.5.3"` 을 함께 핀했다.

**`trustPolicy: no-downgrade` 는 실제로 clean install 을 깨뜨렸다**(`ERR_PNPM_TRUST_DOWNGRADE`,
`semver@6.3.1`·`vite@5.4.21`). 원인은 탈취가 아니라 **구 메이저 유지보수 백포트 오탐**이다 —
pnpm 의 신뢰 검사는 semver 가 아니라 *배포 시각*만 보기 때문에, 신 메이저가 먼저 provenance 를
갖추면 나중에 배포된 구 메이저 백포트가 downgrade 로 오인된다.

| 패키지 | 락 버전 (근거) | 그 이전 배포 중 최강 근거 |
|---|---|---|
| semver | 6.3.1 · 2023-07-10 · 없음 | 7.5.1 · 2023-05-12 · provenance |
| vite | 5.4.21 · 2025-10-20 · provenance | 7.1.3 · 2025-08-19 · trusted publisher |

둘 다 devDependency 전용 전이 의존이라 배포 번들에 없다. `trustPolicyIgnoreAfter`(시점 이전
전부 면제) 대신 **정확한 버전 고정 예외**를 택했고, `semver@6.3.0` 음성 대조군으로 락이 다른
버전으로 올라가면 검사가 자동 재개됨을 확인했다(패키지 전체 면제가 아니다).

**남는 제약**: `minimumReleaseAge`·`trustPolicy` 가 켜지면 frozen install 에서도 락 349건을
매번 재검증한다(≈2.4–4.9s, 네트워크 필요). 완전 오프라인 머신에서 install 을 돌릴 계획이
생기면 `--trust-lockfile` 이 필요하다. 현재 빌드는 네트워크가 있는 개발 머신에서 도는 구조라
실질 영향은 없다.

예외 2건은 **락 갱신 시 재점검 대상**이다. 무지성으로 새 버전에 예외를 옮기면 그 예외가
진짜 탈취를 덮는다 — 위 표처럼 "이전 배포 최강 근거 vs 현재 근거"를 매번 확인해야 한다.

## 8. 재스캔 결과

**15건 → 7건.** 잔여 7건은 전부 §5·§6에서 코드를 열어 확인한 오탐이다.

| 분류 | 조치 전 | 조치 후 |
|---|---|---|
| SCA (심각 3 + 보통 1) | 4 | **0** (`낮은 우선순위 — 도달 불가: 해당 없음`) |
| Secret | 1 | **0** |
| SAST 검토후보 (pnpm) | 3 | **0** |
| SAST 오탐 (BreakGlass 1 · 프로토타입 4 · RedmineImportPanel 2) | 7 | 7 (미조치, 근거 문서화) |
| **합계** | **15** | **7** |

### 8.1 이 검증을 하마터면 놓칠 뻔했다 — secscan BOM 캐시 결함

조치 직후 재스캔에서 **SCA 4건이 그대로 남았다**(trivy 0.5초 = 첫 스캔 8.7초 대비 비정상).
조치 실패가 아니라 **스캐너가 3일 전 의존성을 보고 있었다**:

```python
# security-checker/secscan/sbom.py
def bom_cache_path(target) -> Path:
    h = hashlib.sha1(str(Path(target).resolve()).encode(), ...).hexdigest()[:16]
    return Path(tempfile.gettempdir()) / "secscan-bom" / h / "bom.json"
```

캐시 키가 **대상 경로 문자열뿐**이다(docstring 은 "코드 해시 캐시"라고 하지만 실제로는 경로 해시).
`ensure_bom` 은 파일이 존재하면 그대로 재사용하므로 **의존성이 바뀌어도 BOM 이 영원히 갱신되지 않는다.**
실제로 캐시 파일은 `2026-09-06 23:14` 생성분이었고, 오늘 돌린 조치 전·후 스캔이 **둘 다** 그것을 재사용했다.

| 근거 | 값 |
|---|---|
| jar 실물 (`unzip -l`) | `tomcat-embed-core-10.1.59.jar` · `log4j-api-2.25.5.jar` |
| gradle `dependencyInsight` | `10.1.55 -> 10.1.59` · `log4j-api:2.25.5` |
| cdxgen 도달성 SBOM (오늘 18:32 생성) | `tomcat-embed-core@10.1.59` |
| **trivy SCA (캐시 BOM)** | **`10.1.55` · `2.24.3`** ← 유일하게 틀림 |

캐시(`$TMPDIR/secscan-bom/<sha1(경로)>/`)를 지우고 재실행해서 위 표의 결과를 얻었다.

**이 결함은 반대 방향이 더 위험하다** — 새로 추가한 취약 의존성을 "깨끗함"으로 보고한다.
`secscan` 을 다시 쓸 때는 **의존성을 바꿨으면 BOM 캐시를 먼저 지워야 한다.**
security-checker 저장소에 별건으로 고칠 대상(캐시 키에 의존성 매니페스트 해시 포함).

## 9. 이번 스코프 밖 (별건 권장)

- **`~/.npmrc` 에 npm `_authToken` 평문 보관** — 이 저장소 밖(개발 머신 홈)이라 손대지 않았다.
  공급망 관점에서는 위 정책보다 이 토큰의 유출 영향이 훨씬 크다. 별도 확인 권장.
- `docs/superpowers/plans/*.md` 의 테스트 픽스처 비밀번호 약 20건 — gitleaks 미탐지이고
  대부분 `@SpringBootTest(properties=...)` 상수이며, 완료된 작업의 역사적 기록이라 고쳐 쓰면
  기록이 왜곡된다. 정리하려면 별건으로.
- `docs/design-handoff/prototype/` 스캔 제외 확정 여부(§6).
