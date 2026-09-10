# QA 후속 조치 — 우선순위 및 병렬 작업 분해

QA 리포트(`FINAL-REPORT.md`)의 결함 7건 + 개선 제안을 **파일 소유 경로가 겹치지 않도록** 4개 일감으로 분해한다.
각 일감은 독립 worktree에서 서브 에이전트가 병렬 수행한다.

## 우선순위 근거

| 순위 | 기준 | 해당 |
|---|---|---|
| **P0** | 사용자가 인지할 수 없는 데이터 손실 / 보안 설정이 먹혔는지 검증 불가 | D-1, D-2, D-3 |
| **P1** | 컴플라이언스 목적 무력화 / 관리자가 잘못된 상태를 믿게 됨 | D-4, UX-조상deny, D-8 |
| **P1** | 기능이 절반만 구현되어 사용자 노력이 버려짐 | D-5, D-6 |
| **P2** | 일관성·정확성 결함 (동작은 함) | D-7, P-1 |

## 파일 소유 경로 (레인)

병렬 작업의 유일한 실패 모드는 **같은 파일을 두 에이전트가 고치는 것**이다. 아래 레인은 상호 배타적이다.

| 일감 | 소유 경로 | 충돌 |
|---|---|---|
| W1 | `frontend/src/storage/**` · `frontend/src/state/**` · `components/ShareModal.tsx` · `src/App.tsx` | 없음 |
| W2 | `frontend/src/admin/**` · `backend/**` | 없음 |
| W3 | `components/SearchModal.tsx` · `MoveModal.tsx` · `Sidebar.tsx` · `Editor.tsx` | 없음 |
| W4 | `frontend/src/editor/**` · `components/AttachmentBar.tsx` · `components/TemplateModal.tsx` · `account/SecurityTab.tsx` | 없음 |

**공통 규칙**: 레인 밖 파일(`src/types.ts`, `src/api/**`, 공용 CSS)이 필요하면 **고치지 말고 리포트**한다.

---

## W1 — 저장·동기화 무결성 `P0`

D-1(시드 vault 노출) · D-2(조용한 데이터 유실) · D-3(공유 링크 flush) · P-2(토스트 지속시간)

네 건 모두 "서버 상태와 화면이 어긋나고 사용자는 그 사실을 모른다"는 하나의 주제이고, 전부 `useVaultSync`/`App.tsx` 저장 경로를 건드리므로 **쪼개면 반드시 충돌한다.** 한 일감으로 묶는다.

## W2 — 관리자 정합성 `P1`

D-4(감사 로그 라벨·필터 25종 누락) · D-8(확장자 검증) · UX-높음(조상 deny 위 allow 저장 시 무효 경고)

셋 다 "관리자가 화면을 보고 잘못된 결론을 내린다"는 주제. 백엔드 행위 코드가 단일 출처여야 하므로 backend도 같은 레인.

## W3 — 트리·검색·이동 `P1`

D-5(태그 소비 경로 0개) · D-6(루트로 되돌리기 불가)

둘 다 "기능이 절반만 있어서 사용자 노력이 버려진다". 사이드바-검색 상호작용으로 묶인다.

## W4 — 에디터 위젯·확인 다이얼로그 `P2`

D-7(삭제된 위키링크 stale) · P-1 일부(네이티브 confirm → 앱 모달, index 쪽 3파일)

### 알려진 중복 (의도적)

P-1(confirm 통일)은 6개 파일에 걸쳐 있는데 레인이 셋으로 갈린다.

- W4: `TemplateModal` · `AttachmentBar` · `SecurityTab` → 공용 `ConfirmDialog` 신설 + 적용
- W1: `ShareModal` 취소 확인 → 기존 `MoveWarnDialog`/`LinkWarnDialog` 패턴으로 자체 구현
- W2: `admin/Templates` · `admin/Shares` · `admin/Permissions` → admin 자체 모달 패턴

병렬성을 위해 일시적 중복을 허용한다. **4개 머지 후 `ConfirmDialog` 단일화를 후속 작업으로 처리한다.**

---

## 공통 작업 규칙

- **TDD**: 프로젝트 관례대로 결정 로직을 순수 함수로 추출해 `vitest` 유닛 테스트를 먼저 쓴다 (React 렌더 테스트 없음)
- **JSX 금지**: 모든 컴포넌트는 `React.createElement`
- **검증**: `pnpm test` + `pnpm build`(tsc 통과) / backend 변경 시 `./gradlew test`
- **커밋**: worktree 브랜치에 커밋까지. push·PR·main 머지는 하지 않는다
- 레인 밖 파일이 필요하면 고치지 말고 리포트
