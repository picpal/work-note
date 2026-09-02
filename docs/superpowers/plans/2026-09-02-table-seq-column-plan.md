# 표 seq(번호) 열 지정 + 자동 재번호 — 구현 계획

## 배경

`frontend/src/editor/`의 표 인라인 편집기(Confluence식 위젯)에 "특정 세로열을 번호(seq) 열로
지정하면 1..N이 자동으로 채워지고, 행을 넣고 빼도 자동 재번호되며, 가운데 정렬" 기능을 더한다.

- `gfmTable.ts` — GFM 표 마크다운 ↔ `TableModel` 순수 변환·구조조작. CodeMirror/DOM 의존 없음.
- `tableWidget.ts` — CodeMirror block 위젯. 셀=contenteditable, 구조 연산은 `applyOp`로 모델
  변형 → 재직렬화 → dispatch(위젯 재생성).

## 확정된 설계 결정 (사용자 승인 완료)

**GFM에는 "이 열은 seq 열"이라는 메타데이터를 넣을 자리가 없다.** 표는 순수 마크다운으로만
저장되고, 위젯은 구조 변경마다 통째로 재생성되므로 세션 상태도 남지 않는다. 그래서 표식을
저장하지 않고 **내용으로 감지**한다:

> **seq 열 판정 = `align === "center"` && 본문 행이 1개 이상 && 모든 본문 셀이 정확히 `"1".."N"`**

- 지정(designate)은 명시적 메뉴 액션이다 — 열을 1..N으로 채우고 align을 center로 만든다.
  그 결과 자체가 표식이 되므로 다음부터는 감지로 유지된다.
- **해제 메뉴는 만들지 않는다.** 정렬을 바꾸거나 번호를 손으로 깨면 판정에서 벗어나 자동이
  조용히 멈춘다. 다시 켜려면 메뉴를 다시 누른다.
- 승인된 알려진 한계 4가지 (구현으로 막지 않는다):
  1. 본문 0행 상태에서는 감지할 표식이 없어 지정이 유지되지 않는다.
  2. `1,2,3`이 실제 데이터인 center 정렬 열은 행 조작 시 덮어써진다.
  3. 판정이 내용 기반이라 기존에 저장된 노트에도 소급 적용된다 — 이미 `1,2,3`을 담은 가운데
     정렬 열이 있는 노트는, 사용자가 아무것도 지정하지 않았어도 다음 행 조작부터 자동
     재번호 대상이 된다. 본문이 1행뿐인 표에서는 판정이 사실상 "가운데 정렬 + 셀이 `1`"로
     퇴화하므로 이 소급 범위가 생각보다 넓다.
  4. 행을 지우면(`deleteRow`) 번호가 유지되지만, 같은 행을 범위 선택해 `Delete`로
     비우면(`deleteRange`) seq가 해제된다. 논리적으로 일관되지만 사용자 눈에는 둘 다
     "지우기"로 보인다.

**감지는 반드시 연산 *전* 모델에서 한다.** 행을 삽입하면 그 열이 `1,2,"",3`이 되어 이미
연속이 아니므로, 연산 후에 감지하면 영영 못 잡는다. 순서는 `전 모델에서 seqColumns() → 연산 →
renumberSeq()`.

**seq 유지를 거는 곳은 딱 3곳** — 행 삽입 / 행 삭제 / 멀티셀 붙여넣기.
걸지 않는 곳과 그 이유:

| 연산 | 유지 | 이유 |
|---|---|---|
| 행 삽입·삭제 | O | 행 수가 바뀌어 번호가 깨진다 |
| 멀티셀 붙여넣기 | O | 행이 자동 추가될 수 있다 |
| 열 삽입·삭제 | X | 행 수가 불변이라 번호가 그대로 밀려간다. 게다가 열 인덱스가 밀려서 사전 감지한 인덱스를 그대로 쓰면 엉뚱한 열을 덮어쓴다 |
| 셀 직접 타이핑 | X | 사용자 입력을 되돌리지 않는다(승인된 결정) |
| 범위 삭제(셀 비우기) | X | 위와 같음 — 의도적으로 지운 것을 복구하지 않는다 |

## Global Constraints

- **JSX 절대 미사용.** 이 프로젝트의 모든 컴포넌트는 `React.createElement`(관례상 `const h =`)로
  작성한다. 단 이번 작업 파일들은 React 컴포넌트가 아니라 순수 TS + DOM API이므로 해당 없음.
- **신규 의존성 0.** package.json을 건드리지 않는다.
- **프런트 테스트 관례**: React 렌더 테스트·컴포넌트 테스트를 만들지 않는다. 결정 로직을 순수
  함수로 추출해 vitest 유닛 테스트로 덮는다. 실행은 `cd frontend && pnpm test`(= `vitest run`).
- **TDD**: 실패하는 테스트를 먼저 쓰고, 실패를 눈으로 확인한 뒤 구현한다.
- 주석·테스트 설명은 **한국어**로, 주변 코드의 밀도와 어투에 맞춘다. 기존 `gfmTable.ts` /
  `gfmTable.test.ts` / `tableWidget.ts`의 스타일이 기준이다.
- 기존 공개 함수의 시그니처를 바꾸지 않는다. 새 함수만 추가한다.
- **커밋은 태스크마다 한다.** 커밋 메시지는 한국어, 기존 히스토리 형식(`feat(scope): ...`)을 따른다.

---

## Task 1: `gfmTable.ts`에 seq 순수 함수 4개 + 유닛 테스트

**파일**: `frontend/src/editor/gfmTable.ts`, `frontend/src/editor/gfmTable.test.ts`

기존 `setAlign` 아래, `escapeHtml` 위에 아래 3개 함수를 추가한다. 모두 순수 함수이고 모델을
새로 만들어 반환한다(기존 함수들과 같은 관례 — 입력 모델을 변형하지 않는다).

```ts
/** seq(번호) 열 판정: 가운데 정렬 + 본문 1행 이상 + 본문 셀이 정확히 "1".."N".
    GFM에 메타데이터 자리가 없어 내용 자체가 표식이다 — 지정하면 이 모양이 되고, 이 모양이면 유지된다. */
export function isSeqColumn(m: TableModel, col: number): boolean

/** 모델 전체에서 seq 열 인덱스를 모은다. 구조 연산 *전에* 호출해야 한다 —
    행을 넣고 나면 그 열은 1,2,"",3 이 되어 더 이상 연속이 아니다. */
export function seqColumns(m: TableModel): number[]

/** 지정: 해당 열을 1..N으로 채우고 가운데 정렬. 범위 밖이면 그대로. */
export function setSeqColumn(m: TableModel, col: number): TableModel

/** 주어진 열들을 1..N으로 다시 채운다. 범위 밖 인덱스는 무시. */
export function renumberSeq(m: TableModel, cols: number[]): TableModel
```

(위 주석 4개는 그대로 쓰되, 문장을 다듬어도 좋다. 함수는 4개다 — `isSeqColumn`은
`seqColumns`가 쓰는 판정이지만 테스트가 직접 겨냥할 수 있게 export 한다.)

구현 요건:

- `isSeqColumn(m, col)`: `col`이 `0 <= col < m.header.length` 범위 밖이면 `false`.
  `m.rows.length === 0`이면 `false`. `m.align[col] !== "center"`이면 `false`.
  그 외에는 `m.rows.every((r, i) => r[col] === String(i + 1))`.
  **셀 값은 정확히 일치해야 한다** — `" 1"`, `"1."`, `"01"`은 seq가 아니다(모델의 셀은 파싱
  단계에서 이미 trim되어 있다).
- `seqColumns(m)`: `0..m.header.length-1`을 훑어 `isSeqColumn`이 참인 인덱스 배열.
- `setSeqColumn(m, col)`: 범위 밖이면 `m` 그대로 반환. 아니면 `align[col] = "center"`,
  모든 본문 행의 `[col]`을 `String(i + 1)`로. 본문이 0행이면 정렬만 바뀐다.
- `renumberSeq(m, cols)`: `cols`가 비었으면 `m` 그대로 반환(불필요한 새 객체 금지).
  각 유효 인덱스에 대해 본문 셀을 `String(i + 1)`로. **정렬은 건드리지 않는다** —
  재번호는 번호만 고치는 연산이다.

**테스트** (`gfmTable.test.ts` 끝에 `describe("seq 열", ...)` 블록 추가, 기존 스타일대로 한국어
`it` 설명):

1. `isSeqColumn` — 가운데 정렬 + 1,2,3 인 열은 참
2. `isSeqColumn` — 내용은 1,2,3 이지만 정렬이 center가 아니면 거짓
3. `isSeqColumn` — 정렬은 center지만 번호가 끊기면(1,2,4) 거짓
4. `isSeqColumn` — 본문 0행이면 거짓
5. `isSeqColumn` — 범위 밖 인덱스는 거짓
6. `isSeqColumn` — `"01"`·`" 1"`·`"1."` 같은 유사 표기는 거짓
7. `seqColumns` — seq 열이 둘이면 둘 다 찾는다
8. `setSeqColumn` — 빈 열이 1..N + center로 채워진다
9. `setSeqColumn` — 지정 결과가 곧 `isSeqColumn`을 만족한다(지정→감지 왕복)
10. `setSeqColumn` — 범위 밖이면 모델이 그대로다
11. `renumberSeq` — 행 삽입으로 깨진 번호를 1..N으로 되돌린다
12. `renumberSeq` — 빈 배열이면 같은 객체를 그대로 반환한다
13. `renumberSeq` — 정렬은 바꾸지 않는다
14. **핵심 시나리오(왕복)**: `"| No. | 항목 |\n|:---:|---|\n| 1 | 가 |\n| 2 | 다 |"`를 파싱 →
    `seqColumns`로 `[0]`을 얻고 → `insertRow(m, 1)` → `renumberSeq(next, [0])` →
    `serializeGfmTable` 결과의 번호 열이 `1,2,3`이고 구분행이 `:---:`로 남아 있다

**검증**: `cd frontend && pnpm test` 전체 통과. 새 테스트가 구현 전에 실제로 실패하는 것을
확인하고 나서 구현할 것(TDD).

**커밋**: `feat(table): seq 열 판정·지정·재번호 순수 함수 추가`

---

## Task 2: `tableWidget.ts` — 열 메뉴 항목 + 행 연산 3곳에 seq 유지

**파일**: `frontend/src/editor/tableWidget.ts`

Task 1이 export한 `seqColumns`, `setSeqColumn`, `renumberSeq`를 import 해서 붙인다.

### 2-1. seq 유지 래퍼

`applyOp` 근처에 private 헬퍼를 하나 만든다. 이름은 `keepingSeq`.

```ts
/** 구조 연산을 seq 열 유지로 감싼다. 감지는 반드시 연산 *전* 모델에서 —
    행을 넣고 나면 그 열은 1,2,"",3 이라 더 이상 seq로 보이지 않는다. */
private keepingSeq(fn: (m: TableModel) => TableModel): (m: TableModel) => TableModel {
  return (m) => { const cols = seqColumns(m); return renumberSeq(fn(m), cols); };
}
```

### 2-2. 적용 지점 — 행 삽입·행 삭제·붙여넣기 (호출부 4곳)

기존 `applyOp(...)` 호출 중 아래 네 곳만 `this.keepingSeq(...)`로 감싼다:

1. `onCellKey`의 Tab — 마지막 셀에서 새 행 추가: `this.applyOp((m) => insertRow(m, m.rows.length), ...)`
2. `onCellKey`의 Enter — 마지막 행에서 새 행 추가: 위와 같은 `insertRow` 호출
3. `openRowMenu`의 세 항목 — 위에 행 삽입 / 아래에 행 삽입 / 행 삭제
4. `onCellPaste`의 `applyOp` — 멀티셀 붙여넣기(행 자동 추가)

(항목 번호는 4개지만 "적용 지점 3종"은 행 삽입·행 삭제·붙여넣기를 뜻한다. 위 4개 위치 전부에
건다.)

**감싸지 않는 곳**: `openColMenu`의 열 삽입/삭제/정렬 3+3항목, `deleteRange`. 이유는 위
설계 표 참조. 특히 열 삽입·삭제에 걸면 사전 감지한 인덱스가 밀려서 엉뚱한 열을 덮어쓴다 —
이건 버그이지 누락이 아니다.

### 2-3. 열 메뉴에 지정 항목 추가

`openColMenu`의 항목 배열 맨 끝, 정렬 3항목 다음에 구분선 하나와 항목 하나를 더한다:

```ts
{ sep: true },
{ label: "① 번호 열로 지정", run: () => this.applyOp((m) => setSeqColumn(m, col), { r: 0, col }) },
```

이 항목은 `keepingSeq`로 감싸지 않는다 — 지정 자체가 번호를 채우는 연산이다.

**검증**:
- `cd frontend && pnpm test` — Task 1 테스트 포함 전체 통과(이 파일엔 유닛 테스트를 새로 만들지
  않는다. 프런트 관례상 DOM 위젯은 유닛 테스트 대상이 아니고, 실화면 회귀는 Task 3의 e2e가 맡는다).
- `cd frontend && pnpm build` — 타입 오류 없이 빌드된다. 이게 이 태스크의 주된 자동 검증이다.

**커밋**: `feat(table): 열 메뉴 '번호 열로 지정' + 행 연산 시 자동 재번호`

---

## Task 3: e2e 회귀 스펙

**파일**: `frontend/e2e/specs/editor.spec.ts` (기존 파일에 test 1개 추가)

기존 `test.describe("에디터 화면 (index.html)", ...)` 안에 테스트 하나를 추가한다. 표 위젯에
대한 e2e는 현재 하나도 없으므로, 이 테스트가 표 위젯의 첫 화면 회귀가 된다.

시나리오:

1. 노트를 만들고 본문에 GFM 표를 입력해 위젯이 뜨게 한다
   (표 소스 예: `| No. | 항목 |` / `| --- | --- |` / `|  | 가 |` / `|  | 나 |`)
2. 첫 열 핸들(`.cm-col-handle`)을 클릭해 메뉴를 열고 `① 번호 열로 지정`을 누른다
3. 첫 열 본문 셀이 `1`, `2`가 되고 가운데 정렬(`data-align="center"`)인지 확인한다
4. 행 핸들(`.cm-row-handle`) 메뉴로 `↓ 아래에 행 삽입`을 실행한다
5. 첫 열이 `1`, `2`, `3`으로 자동 재번호됐는지 확인한다

주의사항:

- **셀렉터**: 이 레포에는 `data-testid`가 없다. 한국어 버튼 텍스트·title 속성·클래스 조합으로
  잡는다(`frontend/e2e/lessons.md` #8).
- 표 위젯은 CodeMirror block 위젯이라 커서가 표 안에 있으면 렌더가 소스로 풀린다. 표 입력 후
  커서를 표 밖으로 빼야 위젯이 보인다 — 기존 에디터 spec의 본문 입력 방식을 먼저 읽고 맞춘다.
- 직렬 실행(workers: 1)이고 admin 세션을 공유한다. 만드는 노트 제목엔 `uniq()`를 붙인다.
- 스펙을 쓰기 전에 `frontend/e2e/lessons.md`와 `frontend/e2e/README.md`를 읽는다.

**검증**:
```bash
cd frontend
pnpm e2e:build                       # dist + bootJar (Task 2 변경 반영에 필수)
pnpm e2e e2e/specs/editor.spec.ts    # 이 화면만
```
기존 3개 테스트도 함께 통과해야 한다(회귀 없음).

**커밋**: `test(e2e): 표 번호 열 지정·자동 재번호 회귀`
