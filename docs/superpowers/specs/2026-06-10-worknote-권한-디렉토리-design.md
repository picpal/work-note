# WorkNote — 권한 관리 · 디렉토리 구조 설계

> 폐쇄망 사내 마크다운 웹에디터(work-note)의 **권한 체계**와 **디렉토리(vault) 구조** 설계.
> Claude Design 핸드오프 번들(`work-note/project/`)의 admin 프로토타입을 기반으로, 실제 구현을 위한 모델을 확정한 문서.
>
> - 작성일: 2026-06-10
> - 범위: **권한 모델 + 디렉토리 구조 + 다중 팀(3~4팀) 구조**
> - 비범위: 에디터/렌더링/내보내기 UI, 인증(SSO/LDAP) 연동 상세, 실제 서버 스택 선택

---

## 0. 전제 · 단계(phase)

| 단계 | 환경 | 권한 체계 |
|---|---|---|
| **1단계** | 개인 PC, SQLite, 단일 사용자 | **없음** — 사용자=관리자 1인, 해석기 비활성. `node`+`tag`만 존재 |
| **2단계** | 사내 서버, 공용 서비스 | 본 문서의 전체 권한 체계 활성 |

**핵심 원칙:** `node`/`tag` 스키마는 1·2단계 **동일**. 2단계 전환 시 권한 테이블만 추가 → **1단계 데이터 무손실 마이그레이션**. 단일 사용자 빌드에 ACL 엔진을 넣지 않는다.

---

## 1. 권한 모델 — 3 레이어 + 1 예외 채널

| | 정체 | 메커니즘 |
|---|---|---|
| ① **역할(Role)** | 능력 *상한* | 전역 기능 권한 + 리소스 행위 상한. "무엇을 할 수 있나" |
| ② **ACL** | 리소스 *범위* | `(principal, node) → grant`. 폴더 상속 + 카브아웃 deny. "어디에" |
| ③ **Public** | 전체 *개방* | 폴더 cascade + 노트 exclude. read 전용 |
| ★ **공유 링크** | *예외* | 노트 1개 read 캡. deny를 넘는 유일한 예외. 만료·취소·로깅 |

설계 축: **역할 = 능력 상한, ACL = 리소스 범위, 유효 권한 = 상한 ∩ 범위.**
역할 자체는 리소스 접근을 주지 않는다(관리자 제외). 역할은 *받을 수 있는 최대치*, ACL은 *실제로 받은 것*.

---

## 2. 권한 어휘 (vocabulary)

**전역 기능 권한** (역할에만 존재, 리소스 무관)
- `admin.users` · `admin.permissions` · `admin.roles` · `admin.security` · `admin.audit`

**리소스 행위 상한** (ACL 범위 안에서만 발동)
- `res.read` — 열람
- `res.edit` — 편집 (edit ⊃ read)
- `res.create` — 노트·폴더 생성 (부모 폴더 edit 필요)
- `res.delete` — 삭제(휴지통)
- `res.export` — 내보내기(PDF·MD·클립보드). read 필요. **유출 통제 포인트**
- `res.share` — 공유 링크 생성. read 필요. export의 형제(콘텐츠가 통제 대상 밖으로 나가는 행위)

> **다운그레이드 레버는 역할이다.** ACL grant는 `{read, edit, deny}`의 **노드 전체 단위**라 "edit 주되 일부만 read로" 같은 부분 다운그레이드를 표현할 수 없다. "팀엔 edit, 특정인은 읽기전용"은 그 사람을 **검토자 역할(상한에 edit 없음)**로 두어 캡한다.

---

## 3. 역할(Role)과 팀(Team)

### 3.1 역할 = 능력 상한 (커스텀 가능)
커스텀 역할의 정책은 **실제 enforce되는 권한 집합**(위 어휘)이다. 장식 라벨이 아니다.

| 역할 | 전역 기능 | 리소스 상한 | 비고 |
|---|---|---|---|
| 방문자 | — | `read` | ACL 없음 → 공개 노트만 |
| 운영자 | — | `read·edit·create·delete·export·share` | |
| 검토자(예시 커스텀) | — | `read·export` | 편집 불가. 팀이 edit 줘도 읽기전용으로 캡 |
| 관리자 | `admin.*` 전체 | `res.*` 전체 | 슈퍼유저, ACL/deny 우회 |

### 3.2 팀 = 그룹 (역할 아님)
**팀은 역할이 아니라 그룹이다.** "무엇을 할 수 있나"(역할)와 "누구 소속인가"(팀)는 다른 축. 팀을 역할로 만들면 `팀 × 운영자/검토자`로 조합 폭발한다.

- 팀 = 사람 묶음 + ACL grant 주체. 팀에 스페이스(폴더) 하나 grant → 멤버 전원 상속.
- 내장 그룹 **`@all`** = 전체 인증 사용자. `공용/` 등 모두에게 열 자료의 grant 주체.

---

## 4. 디렉토리(vault) 구조

### 4.1 노드 모델 — 인접 리스트(adjacency list)
- 노드는 **폴더** 또는 **노트** 2 타입. 폴더=컨테이너(내용 없음), 노트=리프(자식 없음).
- 식별은 **안정적 `id`** — grant·공유링크·상속이 전부 id 기준. **이름은 라벨일 뿐**(rename은 권한 무영향).
- `root = 암묵 폴더` — 루트에 폴더·노트 혼합 가능. 루트 생성 = `edit(root)`(기본 관리자).
- 정렬: 같은 부모 내 수동 드래그 순서(`position`). 알파벳·수정일은 보기 옵션.
- 태그: 노트 태그는 트리와 **직교한 횡단 내비게이션 — 권한과 무관**.

저장 모델로 인접 리스트를 택한 이유: 트리가 얕고(3~8 레벨) 권한 상속이 *조상 walk*(재귀 CTE)라 비용이 무시 가능. id 기반이라 rename·move에 path/grant 재작성이 없다(머티리얼라이즈드 패스가 약한 지점).

### 4.2 팀 스페이스 (1급 개념)
```
결제팀/   정산팀/   보안팀/   공용/
```
- **스페이스 = 최상위 폴더 + 소유 팀 메타데이터.** 관례가 아니라 1급.
- 스페이스 생성 시 소유 팀에 edit **자동 grant**.
- `공용/`은 소유 팀 없음 → `@all` 그룹에 read grant(새 노트도 상속), edit는 관리자/지정 팀.
- **단일 vault** 유지(분리 vault 아님): cross-team 참조·전역 검색이 쉽고 빌드가 가볍다. 3~4팀·폐쇄망에 하드 격리는 과함.

### 4.3 라이프사이클
| 동작 | 결과 |
|---|---|
| **rename** | `name`만 변경 — 권한 무영향 |
| **move** | `parent_id` 변경 → 상속 재계산. **`edit(원본)∧edit(대상)` 필요** + 접근 집합 변경 시 **노출 경고** + **cross-space 이동은 강한 경고** + 감사 로그 |
| **delete** | `deleted_at` 세팅(휴지통). 폴더는 **하위째**. acl·share_link **suspend** |
| **restore** | `deleted_at=NULL` → 권한·링크 부활 |
| **purge** | 30일 후 관리자: node + 종속행(acl·tag·share_link) 영구 삭제 |

휴지통의 노드는 일반 탐색·공유링크·검색에서 빠지고, 삭제자/관리자만 복구용으로 본다.

---

## 5. 해석 알고리즘 (resolution)

### 5.1 우선순위 (read 기준)
```
공유링크  >  deny(임의 주체)  >  public  >  역할 기본(default-deny)
```
- **체인 안(같은 레이어):** 조상→자손으로 *가장 가까운 명시 설정*이 그 주체의 값을 정함(nearest-explicit). 한 주체 안에서 deny 아래 재허용은 없다.
- **다중 주체(개인 + 소속 팀 + `@all`):** **deny-우선 합집합**. 적용되는 entry 중 deny가 하나라도 있으면 차단(공유링크만 예외). deny 없으면 모든 allow의 합집합(가장 관대).
- **deny는 절대.** 개인 grant도 팀 deny를 못 뚫는다. 유일한 예외는 만료·취소·열거 가능한 공유 링크.

### 5.2 의사 코드
```text
read(U, N):
  if shareLinkValid(U, N): return true            # deny를 넘는 유일 예외
  if U.role == 관리자: return true
  principals = {U} ∪ teams(U) ∪ {@all}
  entries = [ nearestExplicitAcl(p, N) for p in principals if exists ]   # 각 주체의 가장 가까운 명시 grant
  if any(e.grant == 'deny' for e in entries): return false               # deny 절대
  if any(e.grant in {read, edit} for e in entries): return roleHas(U, res.read)
  if publicRead(N): return roleHas(U, res.read)                          # 폴더 public 상속, 노트 exclude 카브아웃
  return false                                                          # default-deny

edit(U, N):
  if U.role == 관리자: return true                 # 공유링크는 read 전용 → edit 부여 안 함
  entries = [ nearestExplicitAcl(p, N) ... ]
  if any deny: return false
  return any(e.grant == 'edit') ∧ roleHas(U, res.edit)

export(N) = roleHas(res.export) ∧ read(N)
share(N)  = roleHas(res.share)  ∧ read(N)          # 링크 생성 권한
create(F) = roleHas(res.create) ∧ edit(F)
delete(N) = roleHas(res.delete) ∧ edit(N)
admin.X   = roleHas(admin.X)
```
- `nearestExplicitAcl(p, N)`: N→루트 조상 walk, `acl(p, node)`가 있는 첫 노드의 grant. 재귀 CTE.
- `publicRead(N)`: 조상 체인에서 가장 가까운 `public_flag`가 `public`이고, N(또는 더 가까운 조상)에 `exclude`가 없을 것.
- `shareLinkValid(U,N)`: 활성 ∧ 미만료 ∧ 열람수 이내 ∧ (pin 없음 ∨ U∈pin) ∧ U 인증됨.

---

## 6. 공유 링크

| 항목 | 기본값 |
|---|---|
| 생성 권한 | `res.share ∧ read(N)` → 운영자·관리자 ○, 방문자 ✕ |
| 범위 | 노트 1개, **read 전용** |
| 대상 | 인증 직원만(익명 ✕), 특정 사번 pin 선택 |
| 만료 | 기본 7일, 최대 열람수 선택 |
| 취소 | 언제든. 관리자 "활성 링크" 목록에서 일괄 조회·취소 |
| 감사 | 생성·열람·취소 전부 로그 |

> deny 보장은 *"활성 공유 링크라는 열거 가능한 예외를 빼면 절대"*로 정의된다. 숨은 구멍이 생기지 않는다.

---

## 7. 보안 · 가입 · 감사 (프로토타입 정책 계승)

- **가입:** 폐쇄망 — 신청 → 관리자 승인 → **방문자로 활성화**(`requireApproval`).
- **부여 주체:** 관리자(`admin.permissions`)만. **위임 없음**(단, 공유 링크는 `res.share` 보유자가 read 한정으로 생성 가능).
- **역할 강등 시 grant 마스킹:** 운영자→검토자로 낮추면 ACL edit grant는 남되 상한에 막혀 무력화. 사용자 상세 패널에 **"역할 상한에 의해 비활성"** 표시.
- **새 노트 기본 제외:** public 하위에 새 노트 추가 시 *자동으로 명시 `exclude` 엔트리* 삽입(데이터에 박힘) → nearest-explicit이 "제외"로 해석.
- **감사 로그:** 로그인/grant/revoke/deny/public 토글/공유링크/승인/역할변경/이동/삭제 전부 기록. ISMS·PCI-DSS 추적용, CSV·리포트 내보내기.
- **보안 정책:** 비번 최소길이·복잡도·변경주기, 로그인 실패 잠금, 세션 타임아웃, 가입 승인 필수.

---

## 8. SQLite 스키마

### 8.1 트리 — phase 1·2 공통
```sql
CREATE TABLE node (
  id         TEXT PRIMARY KEY,         -- 안정 id (grant·링크·상속의 키)
  parent_id  TEXT REFERENCES node(id), -- NULL = 루트(암묵 폴더 직속)
  type       TEXT NOT NULL,            -- 'folder' | 'note'
  name       TEXT NOT NULL,            -- 폴더명 / 노트 제목
  position   INTEGER NOT NULL,         -- 같은 부모 내 수동 정렬
  content    TEXT,                     -- note 전용 markdown (folder=NULL)
  updated_at TEXT,
  deleted_at TEXT,                     -- NULL=활성, 값=휴지통
  deleted_by TEXT
);
CREATE TABLE tag (node_id TEXT, tag TEXT);   -- 노트 태그 (권한 무관, 횡단)
```

### 8.2 권한 — phase 2에서 추가 (node 스키마는 그대로)
```sql
CREATE TABLE role (id, name, system INTEGER, caps JSON);   -- caps = res.*/admin.* 집합
CREATE TABLE "user" (id, emp, email, role_id, status, last_login);

CREATE TABLE team (id, name);
CREATE TABLE team_member (team_id, user_id);
CREATE TABLE space (node_id PRIMARY KEY, team_id);         -- 1급 스페이스: 최상위 폴더 ↔ 소유 팀

CREATE TABLE acl (                                         -- nearest-explicit 해석
  principal_type TEXT NOT NULL,    -- 'user' | 'team' | 'all'
  principal_id   TEXT NOT NULL,    -- @all이면 센티넬 '@all' (NULL 금지 — SQLite는 PK의 NULL 유니크를 강제 안 함)
  node_id        TEXT NOT NULL,
  grant          TEXT NOT NULL,    -- 'read' | 'edit' | 'deny'  (edit⊃read, deny=전면차단·절대)
  PRIMARY KEY (principal_type, principal_id, node_id));

CREATE TABLE public_flag (node_id, mode);                  -- 'public'(폴더) | 'exclude'(노트)

CREATE TABLE share_link (
  token, node_id, created_by, pinned_emp,
  expires_at, max_views, views, revoked_at);

CREATE TABLE audit (at, who, act, target, ip);
```

---

## 9. 결정 로그 (왜 이렇게)

| # | 결정 | 근거 |
|---|---|---|
| 1 | 역할 = 실제 권한 (커스텀 enforce) | 장식 칩 모순 제거. 검토자=읽기전용 같은 역할이 실제로 동작 |
| 2 | 어휘 Medium (`read/edit/create/delete/export`+`share`) | 7명 규모에 과granular 아님, 생성·삭제·유출은 별도 통제 |
| 3 | 카브아웃 deny (재허용 없음) | "폴더 통부여 + 민감 노트 잠그기" 커버, 감사 단순 |
| 4 | public = 폴더 cascade + 노트 exclude, 새 노트 기본 제외 | 토글 편의 + "무심코 공개" 원천 차단(secure-by-default) |
| 5 | deny 절대 (공유링크만 예외) | deny를 불가침 보장으로. 예외는 열거·취소·만료되는 링크로 분리 |
| 6 | 인접 리스트 + 재귀 CTE | 얕은 트리, id 기반, rename/move 무비용 |
| 7 | move = 위치 따름 + 가드 | 깨끗한 상속 모델 유지, 노출 위험은 경고·권한·감사로 |
| 8 | 휴지통(soft-delete) | KB 지식 손실 방지 + ISMS 보존 |
| 9 | 팀 = 그룹, 단일 vault + 팀 스페이스(1급) | 조합 폭발 방지, cross-team 공유·검색 유지, 하드 격리 회피 |
| 10 | 다중 주체 = deny-우선 합집합 | 5·1·3의 논리적 귀결(선택 아님). 다운그레이드는 역할로 |

---

## 10. 미해결 · 다음 단계

- **인증 연동:** 사번/이메일 인증의 실제 백엔드(LDAP/SSO/자체) — 별도 설계.
- **마이그레이션 도구:** 1단계 SQLite → 2단계 서버 이관 스크립트 — 구현 계획에서.
- **검색과 권한:** 전역 검색 결과를 유효 권한으로 필터링하는 인덱싱 전략 — 구현 계획에서.
- **구현 계획(writing-plans):** 본 스펙 승인 후, 단계별(에디터 → 1단계 SQLite → 2단계 권한) 구현 플랜 작성.
