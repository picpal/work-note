# 최상위 스페이스 폴더 아이콘 구분 — design

날짜: 2026-06-16
범위: 프런트엔드 전용 (백엔드 변경 0)

## 배경

루트(최상위) 폴더는 사실상 관리자가 만든 "스페이스(워크스페이스)"다 — 1급 메타데이터(소유 팀 포함)를 가진 별개 개체. 그런데 트리에서 일반 폴더와 동일한 `folder` 아이콘으로 보여 구분이 안 된다. 최상위 컨테이너임을 시각적으로 구분한다.

## 결정

- **구분 기준: depth (최상위 폴더 여부)**. `node.type === "folder" && depth === 0`.
  - "space 테이블 등록 여부"가 아니라 depth 기반 — 프런트가 이미 아는 정보라 백엔드 작업 0. 사용자에게 보이는 루트는 사실상 모두 스페이스라 의도와 일치.
  - 트레이드오프: 스페이스 미등록 고아 루트도 박스 아이콘이 됨. 단 그런 노드는 일반 사용자에게 안 보이므로(grant 없음) 실사용 무영향.
- **아이콘: 박스/아카이브 모노톤 라인 SVG** (`stroke="currentColor"`, 디자인 시스템 일치). 펼침/접힘은 별도 chevron(`twirl`)이 담당하므로 정적 아이콘 1개로 충분 — open/close 변형 불필요.
- **범위: 사이드바 트리 + 이동 피커의 폴더 옵션.**
  - 이동 피커 "루트(최상위)" 고정 항목은 *스페이스 노드*가 아니라 "루트 레벨로 이동" 동작이므로 제외(그대로 folderOpen).
  - 공유 페이지(share)는 트리 없음 → 해당 없음.

## 변경 지점

| # | 파일 | 변경 |
|---|------|------|
| 1 | `components/Icon.tsx` | `space` 박스/아카이브 라인 아이콘 추가 |
| 2 | `components/Sidebar.tsx` (아이콘 분기 74-78) | `isFolder && depth === 0 → "space"` |
| 3 | `lib/tree.ts` `folderOptions` (137-145) | 반환 객체에 `isRoot: depth === 0` 추가 (walk가 이미 depth 보유) |
| 4 | `components/MoveModal.tsx` (92) | `o.isRoot ? "space" : "folder"` |

## 테스트

- `folderOptions`가 루트 폴더에 `isRoot: true`, 하위 폴더에 `isRoot: false`를 반환.
- (Sidebar/MoveModal 렌더 아이콘 선택은 단위 테스트 가능 범위에서 확인; 기존 `folderOptions` 사용 테스트가 객체 shape 단언 시 갱신.)
