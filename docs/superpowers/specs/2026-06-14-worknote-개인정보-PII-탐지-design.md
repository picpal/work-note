# worknote 개인정보(PII) 탐지 · 표시 · 예외 요청 · 관리자 알림 — 설계

> 상태: 확정(2026-06-14) · 다음 단계: writing-plans
> 관련 스펙: `2026-06-14-worknote-첨부파일-이미지-업로드-design.md`, `2026-06-10-worknote-권한-디렉토리-design.md`

## 1. 목적 / 한 줄 요약

노트 본문을 저장할 때 백엔드가 표준 PII를 탐지해 노트에 **주의 표시**(제목 밑 배너 + 사이드바 경고 아이콘)를 달고, 관리자가 **개인정보 점검 화면**에서 플래그된 노트를 보고 최종 수정자에게 **로그인 팝업 알림**을 보낸다. 작성자/수정자는 오탐일 경우 **예외 요청**을 올리고 관리자가 **허용/반려**하며, 반려 시 노트에 경고가 다시(강조) 표시된다.

## 2. 범위(Scope)

**대상**
- 노트 **본문 텍스트**(markdown content)의 PII 탐지.
- 표시: 에디터 배너, 사이드바 아이콘.
- 예외 요청 워크플로(사용자 요청 → 관리자 허용/반려).
- 관리자 점검 화면(목록 + 예외 요청 대기 처리 + 능동 알림).
- 로그인 시 팝업 알림(수신자에게 대상 노트 제목 목록 표시).

**제외(Out of scope)**
- 첨부 파일 **바이너리 내부**(xlsx/pdf/zip 등) PII 파싱 — 별개 대형 작업.
- 이메일/외부 채널 알림(폐쇄망, SMTP 미보장).
- 유선전화·은행계좌번호 탐지(오탐 과다 → 기본 제외, 후속 여지).
- 작성자(최초) 추적 — 수신자는 **최종 수정자**만 사용.

**모드별 적용**
- **탐지 · 배너 · 사이드바 아이콘**: 백엔드를 경유하는 모든 모드에서 동작(로컬 jar `http` 모드 포함).
- **관리자 목록 · 알림 · 예외 요청**: 사용자/세션이 존재하는 **server 모드** 전용.
- **순수 localStorage dev 모드(`VITE_STORAGE=local`, 무백엔드)**: 기능 비활성(회귀 없음).

## 3. 동작 흐름

```
[사용자 저장(PATCH content)]
   └─> 백엔드 PiiDetector.detect(content)
         ├─ 매칭 없음 → pii_flag 행 삭제(경고 제거)
         └─ 매칭 있음 → pii_flag upsert (상태 기계 §4)
                         · node.updated_by = 세션 사용자
                         · PATCH 응답에 pii:{status,types} 반환 → 프런트 라이브 반영

[노트 화면]
   suspected → 배너 "개인정보 기입 확인" + [예외 요청] 버튼
   사용자 [예외 요청] → status=requested

[관리자 · 개인정보 점검 화면]
   · 예외 요청 대기 목록 → [허용]→exempted / [반려(+사유)]→rejected
   · 전체 플래그 노트 → [알림 보내기](능동)
   결정/알림 → pii_notice 생성(수신자 = 최종수정자/요청자)

[수신자 로그인]
   메인앱 부팅 시 GET /api/me/pii-notices(미확인)
   → 팝업(div 모달)으로 대상 노트 제목 목록 + 종류별 메시지 → [확인]→ack
```

## 4. 상태 기계 (`pii_flag.status`)

| 상태 | 의미 | 노트 배너 | 사이드바 아이콘 |
|---|---|---|---|
| `suspected` | 자동 탐지(미처리) | "개인정보 기입 확인"(주의색) + **[예외 요청]** | `alert` |
| `requested` | 예외 요청 → 관리자 검토 중 | "예외 검토 중"(중립색, 버튼 숨김) | `alert` |
| `rejected` | 관리자 반려 | **"개인정보 예외 반려됨"**(강조색 + 사유) + **[다시 요청]** | `alert` |
| `exempted` | 관리자 허용 | 표시 없음 | 정상(`fileLines`) |

**전이**
- `(없음) ──탐지──▶ suspected`
- `suspected ──[예외 요청]──▶ requested`
- `requested ──[관리자 허용]──▶ exempted`
- `requested ──[관리자 반려]──▶ rejected`
- `rejected ──[다시 요청]──▶ requested`
- 어떤 상태든 `──저장 후 본문에서 PII 전부 제거──▶ (행 삭제)`

→ "반려되면 노트에 또 표시"는 `rejected` 상태가 경고를 다시(강조해) 띄우는 것으로 구현. 사이드바 아이콘은 `exempted`를 제외한 모든 상태에서 경고 유지(미해결 신호).

**저장 시 재탐지 규칙(`PiiService.evaluate`)**
1. `detect(content)` → matched types.
2. matched 비어있음 → `pii_flag` 행 **삭제**(배너/아이콘 사라짐). 응답 status='none'.
3. matched 있음:
   - 행 없음 → INSERT `suspected`(types/detected_at).
   - `suspected` → types/detected_at 갱신, 상태 유지.
   - `requested`·`rejected` → 상태 유지, types/detected_at 갱신(요청 중/반려를 본문 변경만으로 자동 해제하지 않음).
   - `exempted` → matched ⊆ 기존 types면 **exempted 유지**(관리자 검토 완료). matched에 **기존 types에 없던 새 유형**이 포함되면 `suspected`로 복귀 + 요청/결정 필드 초기화.

## 5. PII 탐지기 (`PiiDetector`)

백엔드 순수 함수. `detect(String text) → Set<PiiType>` (매칭 유형 집합 반환). 오탐 억제 위해 가능한 곳에 체크섬 검증.

| PiiType | 1차 정규식(요지) | 오탐 억제 |
|---|---|---|
| `RRN`(주민/외국인등록번호) | `\b\d{6}[-\s]?[1-8]\d{6}\b` | 생년월일 유효성 + 주민번호 체크섬 |
| `PHONE`(휴대폰) | `\b01[016789][-\s]?\d{3,4}[-\s]?\d{4}\b` | — |
| `EMAIL` | 표준 이메일 패턴 | — |
| `CARD`(신용카드) | `\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b` | Luhn 검증 |
| `BIZ`(사업자등록번호) | `\b\d{3}-\d{2}-\d{5}\b` | 사업자번호 체크섬 |
| `PASSPORT`(여권) | `\b[A-Z]\d{8}\b` | — |
| `DRIVER`(운전면허) | `\b\d{2}[-\s]?\d{2}[-\s]?\d{6}[-\s]?\d{2}\b` | — |

- `types` 컬럼 직렬화 = enum name 소문자 CSV(예: `rrn,phone,email`).
- 유형 세트는 코드 상수로 고정(MVP). 후속에 `app_setting` 키로 on/off 설정화 여지(이번 범위 아님).
- 탐지 유형은 **배너에 노출하지 않음**(어깨너머 노출 방지). 관리자 점검 화면에서만 표시.

## 6. 데이터 모델 (Flyway `V5__pii.sql`)

```sql
-- 최종 수정자 추적 (create 시 작성자, content update 시 세션 사용자로 갱신)
ALTER TABLE node ADD COLUMN updated_by TEXT;

CREATE TABLE pii_flag (
  node_id        TEXT PRIMARY KEY REFERENCES node(id),
  status         TEXT NOT NULL CHECK (status IN ('suspected','requested','exempted','rejected')),
  types          TEXT NOT NULL,            -- 탐지 유형 CSV
  detected_at    TEXT NOT NULL,            -- ISO datetime
  requested_by   TEXT, requested_at TEXT, request_reason  TEXT,   -- 사용자 예외 요청
  decided_by     TEXT, decided_at  TEXT, decision_reason TEXT     -- 관리자 허용/반려
);

CREATE TABLE pii_notice (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id   TEXT NOT NULL REFERENCES node(id),
  recipient TEXT NOT NULL,                 -- 수신자 emp
  kind      TEXT NOT NULL CHECK (kind IN ('flagged','approved','rejected')),
  message   TEXT,                          -- 관리자 사유/안내
  sent_by   TEXT NOT NULL, sent_at TEXT NOT NULL,
  ack_at    TEXT                           -- 확인 시각 (NULL=미확인)
);

CREATE INDEX idx_pii_notice_recipient ON pii_notice(recipient, ack_at);
```

- **purge 정리**: 노트 영구삭제(`VaultService.purge`)에서 `node_id` 기준으로 `pii_flag`·`pii_notice`도 cascade 삭제(기존 `public_flag` 정리 패턴 `aclMapper.deletePublicFlagIn(ids)`와 동일하게 mapper 추가).
- **알림 중복 방지**: 같은 `(node_id, recipient, kind)`의 **미확인(ack_at IS NULL)** 행이 있으면 신규 INSERT 대신 `sent_at`/`message` 갱신(재사용).

## 7. API

기존 admin/세션 가드(권한 엔진)를 그대로 적용. 컨트롤러는 신규 `PiiController` + `VaultController.update` 확장.

| 메서드 | 경로 | 권한 | 용도 |
|---|---|---|---|
| PATCH | `/api/nodes/{id}` (확장) | 노트 write | content 저장 후 재탐지. 응답 JSON에 `pii:{status,types}` 추가. `updated_by` 갱신 |
| POST | `/api/nodes/{id}/pii/exception` | 노트 write | 예외 요청 → `requested`. body: `{reason?}`(빈 사유 허용). `suspected`·`rejected`에서만 허용 |
| GET | `/api/admin/pii/notes` | 관리자 | 전체 플래그 노트: `{id,title,updatedBy,types,status,detectedAt}` |
| GET | `/api/admin/pii/requests` | 관리자 | 예외 요청 대기(`status='requested'`): + `requestedBy,requestedAt,requestReason` |
| POST | `/api/admin/pii/notes/{id}/approve` | 관리자 | `exempted`. `pii_notice(kind=approved, recipient=요청자)` 생성 |
| POST | `/api/admin/pii/notes/{id}/reject` | 관리자 | `rejected`. body `{reason}`. `pii_notice(kind=rejected, recipient=요청자, message=reason)` |
| POST | `/api/admin/pii/notes/{id}/notice` | 관리자 | 능동 알림. `pii_notice(kind=flagged, recipient=최종수정자)` 생성. `updated_by` 없으면 400 |
| GET | `/api/me/pii-notices` | 로그인 | 본인 미확인 알림: `[{id,kind,message,noteId,noteTitle}]` |
| POST | `/api/me/pii-notices/ack` | 로그인 | body `{ids:[...]}` 또는 전체. `ack_at` 기록 |

- **GET tree 응답 확장**: `VaultNode`에 `pii` 필드 추가 — `null | { status, types[] }`. (탐지 유형은 프런트 배너에서 사용하지 않지만 향후 관리용으로 내려줌. 배너는 `status`만 사용.) → 사이드바 아이콘·초기 배너 상태 산출.
- **감사 로그**(`AuditService`): `pii.request`(사용자), `pii.approve`/`pii.reject`/`pii.notice`(관리자) 기록. target=node_id.

## 8. 백엔드 컴포넌트

| 컴포넌트 | 파일(신규/수정) | 책임 |
|---|---|---|
| `PiiDetector` | 신규 `com.worknote.pii.PiiDetector` | 순수 탐지 함수 + 체크섬 검증. 의존성 없음 |
| `PiiType` | 신규 enum | 탐지 유형 + CSV 직렬화 헬퍼 |
| `PiiService` | 신규 `com.worknote.pii.PiiService` | `evaluate(nodeId, content)`, `requestException`, `approve`, `reject`, `notice`, `noticesFor(emp)`, `ack` |
| `PiiController` | 신규 | §7의 admin/me/node 엔드포인트 배선 |
| `PiiMapper` | 신규 `mappers/PiiMapper.xml` + 인터페이스 | pii_flag/pii_notice CRUD, 조인 조회(title/updated_by) |
| `VaultController.update` | 수정 `VaultController.java:58-64` | `user(req)` 해석 → `vaultService.update(..., emp)` → `piiService.evaluate(id, content)` → 응답에 pii 포함 |
| `VaultService.update` | 수정 `VaultService.java:90-97` | `updated_by` 갱신 인자 추가(`NodeMapper.updateFields`에 컬럼 추가) |
| `VaultService.purge` | 수정 | pii_flag/pii_notice cascade 삭제 호출 |
| `VaultNode` | 수정 | `pii` 필드 추가 + assemble/create에서 채움 |

- 탐지 비용: PATCH는 프런트에서 1.5초 디바운스 → 탐지는 편집 정지 시 ~1회. 본문 정규식 스캔은 sub-ms, 부하 무시 가능.
- 탐지 실행 경로는 **content update**만(신규 노트는 빈 본문). import/restore 등 다른 경로는 이번 범위 아님.

## 9. 프런트엔드 컴포넌트

| 컴포넌트 | 파일(신규/수정) | 변경 |
|---|---|---|
| 노드 타입 | 수정 `src/types.ts` | `NoteNode.pii?: { status: 'suspected'\|'requested'\|'exempted'\|'rejected'; types: string[] } \| null` |
| 사이드바 아이콘 | 수정 `Sidebar.tsx:70` | 노트가 `pii && status!=='exempted'`면 `fileLines`→`alert` |
| 에디터 배너 | 수정 `Editor.tsx:210`(제목 밑) | 상태별 배너(§4) + 서버 모드에서 [예외 요청]/[다시 요청] 버튼. 비차단 |
| 예외 요청 호출 | 수정 `Editor.tsx` | `POST /nodes/{id}/pii/exception` → 성공 시 `setNotePii(id, {status:'requested'})` |
| 라이브 반영 액션 | 수정 `state/vaultReducer.ts` + `useVault.ts` | **신규 액션 `setNotePii(id, pii)`** — 노드의 pii만 갱신, **디바운스 PATCH를 재유발하지 않음**(`updateNote`와 분리, dirty 미표시) |
| sync 응답 처리 | 수정 `state/useVaultSync.ts` | PATCH 성공 응답의 `pii`를 `setNotePii`로 반영 |
| 관리자 스크린 | 신규 `admin/screens/Pii.tsx` + `AdminApp.tsx`(NAV/TITLES/screenMap) + `admin/api.ts` | "개인정보 점검": 예외 요청 대기 섹션[허용/반려(+사유)] + 전체 플래그 목록[알림 보내기]. 탐지 유형 표시 |
| 로그인 팝업 | 신규 `components/PiiNoticeModal.tsx` + `App.tsx` | 메인앱 부팅 시(server 모드) `GET /api/me/pii-notices` → 미확인 있으면 모달(노트 제목 목록 + 종류별 메시지) → [확인]→ack. 확인 후 재노출 안 함 |

- `alert` 아이콘은 `Icon.tsx`에 이미 존재(삼각형+!). 신규 아이콘 불필요.
- 예외 요청 버튼은 **세션 사용자 존재(server 모드)**에서만 노출. 로컬 jar 모드(무인증)에선 배너만(자기 경고).
- 팝업의 노트 제목은 **이스케이프**해서 렌더(XSS 하드닝 — 기존 정책 준수).

## 10. 테스트 전략

**백엔드(JUnit)**
- `PiiDetector`: 유형별 양성/음성 + 체크섬 경계(유효 주민/사업자번호 vs 형식만 맞는 무효값 → 음성, Luhn 통과/실패 카드).
- `PiiService.evaluate`: 상태 전이 전부 — none→suspected, suspected 유지, exempted+동일유형 유지, exempted+신규유형→suspected 복귀, requested/rejected 유지, PII 제거→행 삭제.
- 예외 요청/허용/반려: 상태·필드·notice 생성. `suspected`/`rejected`에서만 요청 허용(그 외 거부).
- notice 중복 방지: 같은 (node,recipient,kind) 미확인 재사용. ack 처리.
- `notice` 능동 알림: `updated_by` 없으면 400.
- 권한: admin 엔드포인트 비관리자 거부, me 엔드포인트 타인 알림 접근 불가.
- purge: pii_flag/pii_notice 동반 삭제.

**프런트(Vitest)**
- `setNotePii` 리듀서: pii만 갱신, dirty 미표시(디바운스 PATCH 미유발) 회귀.
- 배너 상태별 렌더(버튼 노출/문구).
- 사이드바 아이콘 분기.

**수동/QA**
- server 모드 e2e: 주민번호 입력→배너·아이콘→예외 요청→관리자 허용/반려→반려 시 노트 재표시→로그인 팝업.
- 로컬 dev(무백엔드) 모드: 기능 비활성·회귀 없음.

## 11. 엣지 케이스 / 결정 기록

- **updated_by 부재**: V5 이전 생성·로컬 모드 편집 노트는 `updated_by`가 null일 수 있음 → 다음 content 저장 시 채워짐. null이면 관리자 능동 알림 비활성.
- **통지 수신자**: 관리자 **능동 알림(flagged)** = 최종 수정자(`updated_by`). **허용/반려 통지(approved/rejected)** = **요청자(`requested_by`)**. 통상 동일인이나 분리해 둠.
- **반려 후 재요청**: `rejected`에서 [다시 요청] 허용(`requested` 복귀). 무한 스팸 우려는 폐쇄망·소규모라 정책상 허용(필요 시 후속에 쿨다운).
- **요청 중 경고 약화**: `requested`는 중립색 "예외 검토 중"으로 약화(주의색 아님), 단 사이드바 아이콘은 유지(미해결). "또 표시"는 `rejected` 강조 복귀로 충족.
- **탐지 유형 비노출**: 배너·사이드바는 유형을 드러내지 않음. 관리자만 유형 확인(b).
- **디바운스와 라이브 반영 루프 차단**: PATCH 응답 pii는 `setNotePii`(별도 액션)로만 반영 → `updateNote` 디바운스 재유발 없음.

## 12. 마이그레이션 / 호환

- `V5__pii.sql` 단일 마이그레이션(현재 최신 V4). `node.updated_by`는 nullable 추가라 기존 데이터 호환.
- 기존 노트는 플래그 행이 없어 전부 정상 표시. 다음 저장 시점부터 탐지 적용.
