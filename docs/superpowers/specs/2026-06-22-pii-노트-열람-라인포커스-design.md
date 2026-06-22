# WorkNote — 개인정보 노트 열람 + PII 라인 포커스 설계

> 관리자 개인정보 점검 화면(`Pii`)에서 플래그된 노트의 **본문을 모달로 열람**하고,
> 탐지된 **PII 라인으로 포커스/네비게이션**하는 기능 설계.
> 기존 PII 탐지(`2026-06-14-worknote-개인정보-PII-탐지-design.md`)의 후속 —
> 탐지까지는 되어 있으나, 관리자가 예외 허용/반려를 **본문 없이 메타·사유만 보고** 판단하던 갭을 메운다.
>
> - 작성일: 2026-06-22
> - 범위: 노트 본문 열람 모달 + PII 라인 포커스/네비 + 모달 내 액션 통합 + 열람 감사
> - 비범위: PII 목록 페이지네이션(후속), 마스킹(미적용 확정), 모달 내 본문 직접 편집

---

## 0. 배경 · 문제

현재 `Pii.tsx`의 관리자는 예외 요청을 **탐지 유형(`types`) + 요청 사유(`requestReason`)** 만 보고 허용/반려한다(본문 미열람). 오탐(테스트 데이터·예시 번호)인지 실제 유출인지 구분하려면 매치된 실제 내용을 봐야 한다 → **사실상 깜깜이 판단**.

설계 목표: 점검 대상 노트의 **본문을 열람**하고 **문제 라인으로 포커스**해, 관리자가 근거를 보고 예외를 판단하게 한다.

핵심 제약 — PII 점검 도구가 PII 노출을 늘리는 역설은 **"보여주지 않는다"가 아니라 "통제해서 보여준다"** 로 푼다: ① 매치 라인 중심 포커스(범위 최소) ② 열람을 감사 로깅(추적) ③ 마스킹은 정책상 미적용(폐쇄망 내부망 + 직무상 열람권). ISMS·개인정보보호법 원칙도 "접근권한자 한정 + 접근기록 보존"이지 "절대 미열람"이 아니다.

---

## 1. 확정 결정

| # | 항목 | 결정 |
|---|---|---|
| 1 | 모달 역할 | **열람 + 액션 통합** — 본문·PII 라인 확인 후 그 자리에서 섹션별 액션(허용/반려/알림) |
| 2 | 뷰어 형태 | **원문(raw) read-only**, 경량 자체 뷰어(CodeMirror 미사용) |
| 3 | 긴 노트 렌더 | **고정 행 높이 + no-wrap 가상 윈도잉** — 가시 라인만 DOM. 길이 무관 일정 성능 |
| 4 | 트리거 범위 | **3개 섹션 모두** 클릭(예외 요청 대기 / 전체 개인정보 노트 / 예외 처리됨) |
| 5 | 다중 매치 | 전체 매치 하이라이트 + **다음/이전 네비 + "N개 중 K번" 카운터** |
| 6 | 마스킹 | **없음(평문)** — 정책 확정 |
| 7 | 위치 정보 출처 | **서버가 본문 + 매치 라인 반환** — 정규식은 백엔드 `PiiDetector` 단일 출처 |
| 8 | 본문 fetch | 모달 열 때 **단건 lazy**(목록엔 본문 없음 → 이미 lazy) |
| 9 | 목록 페이지네이션 | **후속**(본 스코프 밖, 모달 기능과 독립) |

---

## 2. 데이터 흐름

```
[Pii 행 클릭]
  → AdminApi.piiNoteContent(nodeId)
  → GET /api/admin/pii/notes/{id}/content        [AdminGuard + 감사 "pii.view"]
  → { nodeId, title, content, matches: [{ type, line, col, value }] }
[PiiNoteViewer 모달]
  → 가상 윈도잉으로 본문 렌더(가시 라인만)
  → 매치 라인 하이라이트, 첫 매치로 scrollIntoView
  → 다음/이전 네비(wrap-around) + "K / N"
  → 섹션별 액션 버튼(허용/반려/알림) → 기존 AdminApi 재사용 → 닫고 load()
```

---

## 3. 백엔드 설계

### 3.1 엔드포인트

`GET /api/admin/pii/notes/{id}/content` — `PiiController`, `adminGuard` 보호.

응답:
```json
{
  "nodeId": "…",
  "title": "노트 제목",
  "content": "원문 전체(마크다운)",
  "matches": [
    { "type": "RRN", "line": 12, "col": 4, "value": "901010-2345678" }
  ]
}
```
- `line`: 1-based. `col`: 라인 내 시작 오프셋(0-based, 같은 줄 정밀 강조용).
- `matches`: 등장 순서(라인·오프셋 오름차순).

### 3.2 `PiiDetector` 위치 확장

기존 `Scan(types, spans)` / `detect()` / `scan()` 은 **그대로 유지**(하위호환). 위치 산출만 추가:

```java
public record Match(PiiType type, int start, int end, String value) {}
public static List<Match> scanMatches(String text)   // 모든 패턴의 Matcher.find() 위치 수집
```
- 라인 계산은 서비스 계층에서: `line = count('\n', text[0..start)) + 1`, `col = start - lastNewlineBefore(start) - 1`.
- 한 번의 본문 순회로 누적 개행 인덱스를 만들어 모든 매치의 라인을 O(n+m)으로 매핑(매치마다 substring 금지).

### 3.3 권한 · 감사

- admin이 임의 노트 본문을 읽는 것은 권한 모델의 **deny 우선 원칙을 넘는 read 예외**(공유 링크와 동급 민감도). → **반드시 감사**.
- `AuditService.log(user, "pii.view", nodeId, ip)` — 누가 어떤 노트의 PII를 열람했는지 기록.
- server 모드: `AdminGuard` 통과 필요. local 모드: 무인증(단일 사용자=관리자).
- 소프트삭제(`deleted_at IS NOT NULL`)·존재하지 않는 노트 → **404**.

### 3.4 감사 라벨

프런트 `mappers`의 `KNOWN_ACTS` / `actLabel`에 `pii.view → "개인정보 열람"` 추가(감사 로그 화면 일관성).

---

## 4. 프런트 설계

### 4.1 API

```ts
interface ApiPiiMatch { type: string; line: number; col: number; value: string }
interface ApiPiiContent { nodeId: string; title: string; content: string; matches: ApiPiiMatch[] }
AdminApi.piiNoteContent = (nodeId) => req<ApiPiiContent>(`/admin/pii/notes/${enc(nodeId)}/content`)
```

### 4.2 `PiiNoteViewer.tsx` — 가상 윈도잉 뷰어

- 입력: `content`, `matches`. `lines = content.split("\n")`.
- **고정 행 높이 `ROW`(예: 20px) + no-wrap**(긴 줄은 가로 스크롤). 가변 높이 측정 불필요 → 라인 점프 정확.
- 가상화(의존성 0, 직접 구현):
  - `scrollTop → startIdx = floor(scrollTop/ROW)`, `endIdx = startIdx + ceil(viewportH/ROW) + overscan`.
  - 위 spacer `startIdx*ROW`, 아래 spacer `(N-endIdx)*ROW`, 중간에 `[startIdx, endIdx)` 라인만 렌더.
- 매치 강조: 매치가 있는 라인은 문자열 인덱스(`col`, `value.length`) 기준으로 `[before, <mark>value</mark>, after]` **세그먼트 분할** 렌더.
  - React 텍스트 노드로만 출력(`dangerouslySetInnerHTML` 미사용) → XSS 안전.
  - 같은 값이 한 줄에 여러 번 → 각 매치 `col` 기준으로 정확히 분할.
- 포커스/네비:
  - `active` 매치 인덱스 state. 변경 시 컨테이너 `scrollTop = matchLine*ROW - viewportH/2`(가운데 정렬)로 점프 + active 라인 강조.
  - 다음/이전 버튼(wrap-around), "K / N" 카운터.

### 4.3 순수 함수 분리(테스트 대상)

`lib/pii`(또는 `lib/piiViewer`)에 다음을 순수 함수로 추출 — CLAUDE.md 테스트 관례:
- `splitLineSegments(lineText, matchesOnLine) → segments[]`
- `matchesByLine(matches) → Map<lineIdx, match[]>`
- `nextMatchIndex(cur, total, dir) → idx`(wrap-around)
- `visibleRange(scrollTop, viewportH, ROW, total, overscan) → {start, end}`

### 4.4 모달 · `Pii.tsx` 배선

- 모달: 기존 `common.Modal`(wide) 재사용. footer에 **섹션별 액션**:
  - 예외 요청 대기 → **허용 / 반려**(기존 `approve`/`reject` 흐름)
  - 전체 개인정보 노트 → **알림 보내기**(기존 `notify`)
  - 예외 처리됨 → 읽기 전용(액션 없음)
- `Pii.tsx`: 각 행 `onClick` → `setViewing({ nodeId, source })` → `piiNoteContent` fetch → 모달.
  - 행 우측 기존 액션 버튼은 `onClick`에서 `e.stopPropagation()` → 행 클릭과 충돌 방지.

---

## 5. 성능 · 확장성

| 축 | 동작 | 평가 |
|---|---|---|
| 노트 **수** 증가 | 모달은 항상 **단건** fetch | ✅ 총량 무관(O(1)) |
| 노트 **수** 증가 | 목록(`adminList`)은 전체 반환·페이지네이션 없음 | ⚠️ 기존 이슈 → **후속**(§9) |
| 노트 **길이** 증가 | 본문 단건 fetch + `scanMatches` 선형(ReDoS 위험 낮음: anchored char-class) | ✅ 온디맨드 수용 |
| 노트 **길이** 증가 | 가상 윈도잉 → 가시 라인만 DOM | ✅ 길이 무관 일정 성능 |

`scanMatches`는 노트 저장 시점 탐지와 중복이나, 위치는 저장하지 않으므로 열람 시 재계산 불가피(단건·온디맨드라 부담 작음). 캐싱은 도입하지 않음(YAGNI).

---

## 6. 엣지 케이스

| 상황 | 처리 |
|---|---|
| 본문 없음 / 소프트삭제 | 서버 404 → 모달 "본문을 불러올 수 없습니다" |
| 매치 0개(값이 바뀌어 더 이상 PII 아님) | 본문만 렌더, "탐지된 항목 없음" 안내 + 네비 숨김 |
| 같은 값이 한 줄에 다회 출현 | `col` 기준 세그먼트 분할로 정확히 처리 |
| 매우 긴 줄(no-wrap) | 가로 스크롤. PII 값은 대개 짧아 부담 작음 |
| 거대 본문(수 MB) | fetch 페이로드 큼(폐쇄망 내부망 수용). 비정상 노트로 간주, 별도 상한 미도입 |

---

## 7. 테스트 전략

- **백엔드(JUnit)**:
  - `PiiDetector.scanMatches` — 위치/오프셋, 다중 매치, 빈 텍스트, 다국어 라인.
  - 라인 매핑 — 개행 누적 인덱스로 line/col 산출 정확성.
  - `PiiService.noteContent` — 본문 read + 매치 결합 + 404(삭제/부재), 감사 1건 기록.
  - 권한 — `AdminGuard`(server 모드).
- **프런트(vitest, 순수 함수)**:
  - `splitLineSegments` — 단일/다중/인접 매치, 경계.
  - `matchesByLine`, `nextMatchIndex`(wrap), `visibleRange`(경계·overscan).
- 실화면 회귀는 별도 QA(`/qa browse`).

---

## 8. 보안 고려

- **평문 노출**: 마스킹 없이 본문 평문 표시(정책 확정). 노출 통제는 범위 최소(라인 포커스) + 감사(`pii.view`)로 대체.
- **deny 우회 예외**: admin 본문 read는 권한 모델 예외 채널. 공유 링크와 동급으로 **모든 열람을 감사**.
- **XSS**: 본문은 React 텍스트 노드로만 렌더, `<mark>` 강조도 세그먼트 분할(인덱스 기반). `dangerouslySetInnerHTML` 미사용.
- **로컬 vs 서버 모드**: local 무인증(단일 관리자), server는 `AdminGuard` + 감사.

---

## 9. 비범위 · 후속

- **PII 목록 페이지네이션** — `adminList`/`adminRequests`에 limit/offset(감사 화면 패턴 재사용). 플래그·exempted 장기 누적 대비. 본 모달과 독립.
- **본문 직접 편집** — 모달에서 관리자가 PII 제거. 권한·감사·작성자 소유권 충돌로 보류.
- **마스킹 토글** — 정책상 미적용. 추후 외부망 확장 시 재검토.
