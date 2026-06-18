import type { VaultTree } from "./types";

const README = `사내 결제(PG) 운영 지식을 한곳에 모아두는 **로컬 우선** 마크다운 노트 도구입니다. 폐쇄망 환경을 전제로 하며, 초기에는 개인 PC에서 SQLite로 동작하고 이후 사내 서버의 공용 서비스로 확장합니다.

> 이 문서는 vault의 진입점입니다. 좌측 사이드바에서 폴더를 펼쳐 노트를 탐색하고, \`⌘ K\` 로 제목·본문을 검색하세요.

## 핵심 원칙

1. **가독성 우선** — 흰 바탕에 검정·회색 모노톤. 화면을 오래 봐도 눈이 편하도록 설계했습니다.
2. **로컬에서 동작** — 개인 PC의 SQLite에 저장. 네트워크 없이도 모든 기능이 동작합니다.
3. **마크다운 그대로** — 원문은 항상 \`.md\`. 언제든 그대로 내보내거나 다른 도구로 옮길 수 있습니다.

## 무엇을 지원하나요

| 기능 | 설명 |
| --- | --- |
| 라이브 프리뷰 | 블록을 클릭하면 원본, 벗어나면 즉시 렌더링 |
| 다이어그램 | Mermaid 플로우차트 · 시퀀스 다이어그램 |
| 코드 하이라이팅 | 다수 언어 지원, 모노톤 테마 |
| 내보내기 | PDF · Markdown · 클립보드 |

## 빠르게 시작하기

- [x] 사이드바에서 폴더 우클릭 → **새 노트**
- [x] 본문 아무 곳이나 클릭해 바로 작성
- [ ] \`⌘ K\` 로 노트 검색해보기
- [ ] 노트 우클릭 → **내보내기**로 PDF 저장

작성 규칙·온보딩은 \`시작하기\` 폴더를, 시스템 구조는 \`아키텍처\` 폴더를 참고하세요.`;

const PIPELINE = `결제 요청부터 정산까지의 데이터 흐름과, 각 단계에서 노트로 남겨야 하는 운영 지식의 위치를 정리합니다.

## 전체 흐름

\`\`\`mermaid
flowchart TD
  A[가맹점 결제 요청] --> B{유효성 검증}
  B -- 실패 --> E[에러 응답코드 반환]
  B -- 통과 --> C[PG 승인 요청]
  C --> D[카드사 / 은행 승인]
  D -- 승인 --> F[거래원장 기록]
  D -- 거절 --> E
  F --> G[정산 배치]
  E --> H[운영 노트: 응답코드 사례 누적]
\`\`\`

## 단계별 노트 매핑

각 단계에서 발생하는 이슈는 **사건 단위가 아니라 개념 단위**로 한 페이지에 누적합니다.

- **검증 단계** → \`응답코드\` 노트에 신규 코드/사례 추가
- **승인 단계** → \`아키텍처/승인 연동\` 노트
- **정산 단계** → \`운영 가이드/정산 배치\` 노트

> 새 사례가 생기면 새 노트를 만들지 말고, 해당 개념 노트에 날짜와 함께 덧붙이세요. 그래야 검색 한 번으로 맥락 전체가 잡힙니다.`;

const APPROVAL = `가맹점 → 자사 게이트웨이 → 카드사로 이어지는 승인 요청의 표준 시퀀스입니다. 타임아웃·재시도 정책을 함께 기록합니다.

## 승인 요청 시퀀스

\`\`\`mermaid
sequenceDiagram
  participant M as 가맹점
  participant G as 결제 게이트웨이
  participant V as 검증 모듈
  participant C as 카드사
  M->>G: 승인 요청 (POST /pay)
  G->>V: 카드번호 · 한도 검증
  V-->>G: 검증 결과
  G->>C: 승인 전문 전송
  C-->>G: 승인/거절 코드
  G-->>M: 응답 (JSON)
  Note over G,C: 타임아웃 7s · 1회 재시도
\`\`\`

## 타임아웃 정책

| 구간 | 타임아웃 | 재시도 |
| --- | --- | --- |
| G → V | 2s | 없음 |
| G → C | 7s | 1회 |
| 전체 | 12s | — |

\`\`\`python
# 승인 요청 의사 코드
def request_approval(payload, timeout=7, retries=1):
    for attempt in range(retries + 1):
        try:
            resp = card_network.send(payload, timeout=timeout)
            return resp
        except Timeout:
            if attempt == retries:
                return error("ECONN_TIMEOUT")  # → 응답코드 노트 참조
            continue
\`\`\`

자세한 응답코드 매핑은 \`운영 가이드/응답코드\` 노트를 보세요.`;

const CODES = `PG 응답코드와 운영 대응을 누적하는 개념 노트입니다. 새 코드가 관측되면 표에 행을 추가하세요.

## 자주 보는 코드

| 코드 | 의미 | 운영 대응 |
| --- | --- | --- |
| \`0000\` | 정상 승인 | — |
| \`0051\` | 한도 초과 | 고객에게 한도 안내, 재시도 금지 |
| \`0061\` | 1일 한도 초과 | 익일 재시도 유도 |
| \`ECONN_TIMEOUT\` | 카드사 응답 지연 | 7s 후 1회 재시도, 미응답 시 보류 |
| \`9999\` | 미정의 오류 | 즉시 에스컬레이션 |

## 대응 체크리스트

- [x] 코드 의미를 카드사 규격서와 대조
- [x] 재시도 가능 여부 확인
- [ ] 반복 발생 시 \`아키텍처/승인 연동\` 에 패턴 기록
- [ ] 고객 안내 문구 표준화

> \`9999\` 처럼 미정의 오류는 자동 판단 대상이 아닙니다. **사람이 판단·에스컬레이션** 합니다.`;

const ONBOARD = `새로 합류한 운영자를 위한 노트 작성 규칙입니다.

## 노트 작성 규칙

1. **개념당 한 페이지** — 사건이 아니라 개념(entity) 단위로 만듭니다.
2. **누적** — 새 사례는 기존 노트 하단에 \`### YYYY-MM-DD\` 헤더로 덧붙입니다.
3. **출처 명시** — 카드사 규격서·티켓 번호 등 근거를 함께 적습니다.

## 폴더 구조

\`\`\`
시작하기/     온보딩 · 작성 규칙
아키텍처/     시스템 구조 · 연동 시퀀스
운영 가이드/  응답코드 · 정산 · 장애 대응
회의록/       주간 운영 회의
\`\`\`

준비가 되면 \`README\` 를 다시 읽고 첫 노트를 작성해보세요.`;

const MEETING = `### 2026-06-08 주간 운영 회의

**참석:** 운영팀 4인 · 개발 2인

## 안건

- [x] 6월 1주 응답코드 \`0061\` 급증 원인 공유
- [x] 정산 배치 지연(평균 +14분) 모니터링 강화
- [ ] 신규 가맹점 온보딩 체크리스트 v2 초안

## 결정 사항

> \`0061\`(1일 한도 초과)은 특정 가맹점 프로모션 트래픽이 원인으로 확인. 한도 사전 조정 프로세스를 \`운영 가이드\` 에 문서화하기로 함.

## 액션 아이템

| 담당 | 항목 | 기한 |
| --- | --- | --- |
| 운영 A | 한도 조정 SOP 문서화 | 6/12 |
| 개발 B | 정산 배치 알림 임계치 조정 | 6/11 |`;

let _id = 0;
const uid = () => "n" + (++_id);

export const SEED: VaultTree = [
  {
    id: uid(), type: "folder", name: "시작하기", open: true, created: "2026-06-01T09:00:00", children: [
      { id: uid(), type: "note", title: "온보딩 가이드", tags: ["가이드"], updated: "2026-06-02", created: "2026-06-02T10:00:00", content: ONBOARD },
    ]
  },
  {
    id: uid(), type: "folder", name: "아키텍처", open: true, created: "2026-06-05T09:00:00", children: [
      { id: uid(), type: "note", title: "결제 파이프라인", tags: ["설계", "flow"], updated: "2026-06-07", created: "2026-06-07T11:00:00", content: PIPELINE },
      { id: uid(), type: "note", title: "승인 연동 시퀀스", tags: ["설계", "카드사"], updated: "2026-06-06", created: "2026-06-06T14:00:00", content: APPROVAL },
    ]
  },
  {
    id: uid(), type: "folder", name: "운영 가이드", open: false, created: "2026-06-08T09:00:00", children: [
      { id: uid(), type: "note", title: "응답코드", tags: ["운영", "레퍼런스"], updated: "2026-06-08", created: "2026-06-08T15:00:00", content: CODES },
      { id: uid(), type: "folder", name: "장애 대응", open: false, created: "2026-06-08T16:00:00", children: [] },
    ]
  },
  {
    id: uid(), type: "folder", name: "회의록", open: false, created: "2026-06-08T18:00:00", children: [
      { id: uid(), type: "note", title: "2026-06-08 주간 회의", tags: ["회의록"], updated: "2026-06-08", created: "2026-06-08T18:30:00", content: MEETING },
    ]
  },
  { id: uid(), type: "note", title: "README", tags: [], updated: "2026-06-09", created: "2026-06-09T08:00:00", content: README },
];

// id of note opened by default (most-recently viewed)
export const SEED_DEFAULT_TITLE = "README";
