# work-note e2e QA 시나리오 카탈로그

`/qa`(gstack)로 실행하는 전체 기능 e2e 테스트 시나리오. 각 Batch = 한 번의 `/qa` 호출 단위.
호출 형식: `/qa http://localhost:8080/login.html <시나리오 본문>`

## 자동화 가능성 범례

| 표기 | 의미 |
|---|---|
| ✅ | 브라우저 자동화 가능 (폼·클릭·네비·콘텐츠) |
| ⚠️ | 부분 — 타이밍/멀티세션/다운로드 등 보조 필요 |
| 🔧 | 브라우저 자동화 부적합 → API 호출/단위테스트로 커버 |

## 0. 실행 준비

### 0.1 server 모드 기동 (임시 DB 권장)
```bash
cd frontend && pnpm build && cd ../backend && ./gradlew bootJar
rm -f /tmp/wn-qa.db*
WORKNOTE_MODE=server WORKNOTE_ADMIN_PASSWORD=qa-admin-1234 WORKNOTE_DB=/tmp/wn-qa.db \
  java -jar build/libs/worknote-0.1.0.jar   # 백그라운드 기동
# 헬스: curl --retry-connrefused --retry 40 --retry-delay 1 http://localhost:8080/api/health
```
- 로그인 계정: 사번 `admin` / 비번 `qa-admin-1234` (부트스트랩 관리자)
- 빈 DB면 첫 진입 시 시드 vault 자동 업로드(시작하기/아키텍처/운영 가이드/회의록/README)

### 0.2 픽스처 시딩 (권한·공유·관리자 시나리오 전제)
admin 세션 쿠키로 제한 역할 유저·팀·ACL·public·공유 링크를 미리 생성. (Batch 5~8 전제)
```bash
J=/tmp/wn-qa-cookies.txt; B=http://localhost:8080/api
curl -s -c $J -X POST $B/auth/login -H 'Content-Type: application/json' -d '{"emp":"admin","password":"qa-admin-1234"}' >/dev/null
# 제한 역할 유저(operator) + 대기 유저(visitor)
curl -s -b $J -X POST $B/admin/users -H 'Content-Type: application/json' -d '{"emp":"OP1","name":"운영자","roleId":"operator","password":"op-12345678"}'
curl -s -X POST $B/auth/signup -H 'Content-Type: application/json' -d '{"emp":"PEND1","name":"대기자","password":"pend-12345678"}'   # pending
# 팀 + 멤버
TID=$(curl -s -b $J -X POST $B/admin/teams -H 'Content-Type: application/json' -d '{"name":"품질팀"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
# 노드 id는 GET /api/tree에서 확인 후 ACL/public/share 대상 지정
curl -s -b $J $B/tree | python3 -m json.tool | head -40
```
> 노드 id를 얻은 뒤 ACL(`PUT /api/admin/nodes/{id}/acl`)·public(`PUT /api/admin/nodes/{id}/public`)·공유(`POST /api/nodes/{id}/share`)를 시딩.

### 0.3 React 컨트롤드 입력 채우기 (자동화 팁)
입력칸에 `id`/`name`이 없어 접근성 트리로 안 잡히는 폼은 네이티브 setter로 채운다:
```js
function setVal(el,v){const s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));}
```
React state는 클릭 직후 동기 조회 불가 → 검증 메시지/토스트는 `setTimeout` 후 읽는다.

---

## Batch 1 — 인증 (login.html, server) ✅

```
/qa http://localhost:8080/login.html 사번 admin / 비번 qa-admin-1234 환경.
1) 로그인 성공: admin/qa-admin-1234 → /index.html 진입, 우상단 "관리자 (admin)"
2) 로그인 실패(틀린 비번): admin/wrong → 에러 노출, 페이지 유지(리다이렉트 없음)
3) 로그인 실패(없는 사번): NOPE/whatever1234 → 2)와 동일 문구(계정 존재 노출 금지)
4) 가입 탭 전환 → 9자 비번(123456789) → "비밀번호는 10자 이상이어야 합니다"
5) 가입 비번 불일치: 10자 비번 + 다른 확인값 → "비밀번호가 일치하지 않습니다"
6) 가입 성공: 사번 NEWUSER1 / 이름 신규 / 비번 newpass-1234 → "가입 신청" 완료 안내(관리자 승인 대기)
7) 가입 중복 사번: admin 으로 가입 시도 → 409 중복 에러 문구
```
**기대 체크리스트**: 1=라벨 일치 · 2·3=동일 에러 문구 · 4·5=클라 검증 · 6=pending 안내 · 7=409
**자동화 노트**: ✅ 전부. 비활성/대기 계정 로그인 차단(403)은 픽스처 PEND1 로 추가 가능.

## Batch 2 — 본인 프로필·비밀번호 (index, server) ✅
*(이미 1차 검증 완료 — 회귀 batch)*

```
/qa http://localhost:8080/login.html admin/qa-admin-1234 로그인 후 우상단 프로필 모달.
1) 이름→"관리자둘", 이메일→"admin2@corp.local" 저장 → 토스트 "프로필을 저장했습니다" + 우상단 라벨 즉시 "관리자둘 (admin)"
2) 새 비번 "abc"(현재·확인 포함) → "새 비밀번호는 10자 이상이어야 합니다."
3) 현재 qa-admin-1234 + 새 "newpass-1234" → 토스트 "비밀번호를 변경했습니다", 변경 후 /api/auth/me 200(세션 생존)
4) 모달 재오픈 시 이메일이 서버값(admin2@corp.local) 프리로드 확인
```
**자동화 노트**: ✅. 비번 변경 시 **타 기기 세션 무효화**는 🔧 (2개 세션 필요 → API 통합테스트 ChangePasswordApiTest가 커버).

## Batch 3 — 에디터·노트 편집 (index) ✅⚠️

```
/qa http://localhost:8080/login.html admin/qa-admin-1234 로그인 후 에디터.
1) 새 노트 생성(툴바 새 노트 버튼) → 인라인 rename "QA노트" → Enter
2) 본문에 텍스트 입력 → 1.5초 후 "저장되었습니다" 토스트(디바운스 저장)
3) 툴바 액션 각각 클릭해 마크다운 삽입 확인: H1, 굵게, 기울임, 인용, 목록, 체크리스트, 표, 링크, 이미지, 코드블록, Mermaid, 시퀀스
4) 헤딩 여러 개 입력 → 우측 아웃라인(목차)에 반영 + 항목 클릭 시 해당 위치로 점프
5) ⌘K 검색 → "README" 입력 → 결과에서 노트 열기
6) 새 폴더 생성 → rename "QA폴더"
7) 노트 우클릭 → 삭제 → "노트를 삭제했습니다" 토스트(휴지통 이동)
```
**자동화 노트**: 1·6·7 ✅ · 3·4·5 ✅ · 2 ⚠️(디바운스 1.5초 대기 필요). 내보내기(exportCommands)는 파일 다운로드라 ⚠️(다운로드 경로 검증).

## Batch 4 — 트리 이동 (index, server) ✅⚠️🔧

```
/qa http://localhost:8080/login.html admin/qa-admin-1234 로그인 후 트리.
1) 폴더/노트 우클릭 → "이동" → 폴더 피커에서 다른 폴더 선택 → 이동 확인
2) 노출이 넓어지는 이동(공개 폴더로/다른 스페이스로) → "노출 범위가 넓어집니다" 경고 모달 → 계속/취소
3) 폴더를 자기 자신/자손으로 이동 시도 → 후보 목록에서 제외(선택 불가)
4) 루트로 이동
```
**기대**: 1·4=위치 변경 · 2=경고 모달 분기(강한 경고는 danger) · 3=가드
**자동화 노트**: 1·2·4 ✅(컨텍스트 메뉴 경로). **DnD(노트→폴더 드래그) 🔧** — HTML5 네이티브 drag는 헤드리스 자동화로 신뢰성 없음 → 실브라우저 수동 + `lib/dnd.ts canDropOn` 9 단위테스트로 커버.

## Batch 5 — 휴지통 (index, server) ✅🔧

진입: 사이드바 푸터 **휴지통 버튼**(http 모드 전용) → TrashModal.
```
/qa http://localhost:8080/login.html admin/qa-admin-1234.
1) 노트/폴더 삭제(우클릭 → 삭제) → 휴지통 버튼 클릭 → 모달 목록에 노출(라벨·노트/폴더 구분)
2) 모달에서 복구 → 토스트 "복구했습니다" + 원위치 트리 재동기화(부모 폴더 펼치면 노출)
3) 영구 삭제 → "영구 삭제 확인" 2단계 → 토스트 "영구 삭제했습니다" + 목록에서 제거
4) 빈 상태 "휴지통이 비어 있습니다"
```
**자동화 노트**: 1·2·3·4 ✅(2026-06-14 UI 신설 — `VaultApi.trashList/restore/purge` + `TrashModal`, 백엔드 `GET /trash`·`POST /trash/{id}/restore`·`DELETE /trash/{id}` 연결). **30일 자동 purge 🔧** — 시간 의존, 스케줄러 단위테스트로 커버(WORKNOTE_PURGE_RETENTION_DAYS). 알려진 사소: 복구 시 트리 reload가 폴더 펼침 상태를 리셋(데이터는 정상).

## Batch 6 — 공유 링크 (index→share.html, server) ✅⚠️

*전제: 픽스처에서 노트 1개 공유 링크 생성하거나 시나리오 1에서 생성.*
```
/qa http://localhost:8080/login.html admin/qa-admin-1234.
1) 노트 우클릭 → "공유 링크" → days=7 생성 → 토큰/URL 발급
2) 발급된 share.html?token=... 새 탭 열람 → read-only 본문 표시(편집 UI 없음)
3) 링크 취소(revoke) 후 동일 토큰 열람 → 404(무효 사유 단일 404)
4) 관리자 공유 링크 화면(admin Shares)에서 활성 링크 목록·취소
```
**기대**: 1=발급 · 2=read-only 렌더 · 3=취소 후 404 · 4=admin 목록
**자동화 노트**: 1·2·4 ✅ · 3 ✅. **pin 사번/maxViews/만료** ⚠️(별도 토큰 생성·조작 필요, API로 셋업 후 브라우저 열람). deny 노트도 공유로 read 되는 예외는 🔧(ACL 픽스처 필요 → API 검증).

## Batch 7 — 관리자 화면 (admin.html, server, admin) ✅

```
/qa http://localhost:8080/login.html admin/qa-admin-1234 로그인 후 admin.html.
1) Dashboard: 요약 카드 표시
2) Users: 목록 / 사용자 추가(10자 비번) / 이름·역할·상태 수정 / 비번 초기화
3) Pending: 가입 대기자 승인 → active 전환
4) Roles: 역할 생성(caps 화이트리스트) / 수정 / 삭제(시스템 역할·사용중 차단)
5) Teams: 팀 생성 / 멤버 추가·제거 / 삭제
6) Permissions(ACL): 노드 선택 → 주체별 grant/deny replace-all 저장
7) Spaces: 최상위 폴더 스페이스 지정/해제
8) Public: 노드 public/exclude 설정·해제
9) Security: 비밀번호 최소 길이 "10자 (최대 128자)" 표시 확인
10) Audit: who/act/from/to 필터 조회, act 라벨 한글화(프로필 수정·비밀번호 변경 포함)
```
**기대 핵심**: 2=10자 검증·자기역할 변경 차단(422) · 3=pending→active · 4=마지막 admin 락아웃 방지(422) · 9=문구 10자 · 10=`auth.profile.update`/`auth.password.change` 라벨
**자동화 노트**: ✅ 전부(폼·테이블). 비번초기화 후 **세션 즉시 무효화**는 🔧(대상 세션 별도).

## Batch 8 — 권한 적용 (index, server, 제한 역할) ✅🔧

*전제: 픽스처 OP1(operator) + 일부 노드 ACL deny/grant + public.*
```
/qa http://localhost:8080/login.html OP1/op-12345678 로 로그인(제한 역할).
1) 트리에 읽을 수 있는 노드만 노출(경로 연결용 조상 폴더는 이름 스텁)
2) 편집 권한 없는 노트 열어 편집 시도 → 저장 실패(403) 처리
3) deny 우선: 개인 grant가 있어도 팀 deny 노드는 안 보임
4) public 폴더 하위 새 노트는 자동 exclude(기본 비공개)
```
**자동화 노트**: 1·2 ✅(픽스처 의존). 3·4 🔧(ACL/그래프 상태 셋업 → API 검증이 더 정확). deny-sticky·합성 규칙은 백엔드 단위테스트로 커버.

## Batch 9 — 설정·테마·단축키 (index) ✅

```
/qa http://localhost:8080/login.html admin/qa-admin-1234 로그인 후 설정.
1) 다크 모드 토글(상단 달/해 버튼) → data-theme 전환 + 새로고침 후 지속
2) 설정 모달: 밀도(compact/comfortable/spacious) → row 높이 변화
3) 사이드바 너비/본문 폰트 크기/가이드선/아이콘 토글 반영
4) ⌘K(검색)·⌘\(사이드바 접기) 단축키
```
**자동화 노트**: ✅. 지속성(localStorage)은 새로고침 후 재확인.

## Batch 10 — 모드·복원력 엣지 (혼합) ⚠️🔧

```
별도 환경/조작 필요:
1) local 모드 기동(무인증) → index 바로 진입, admin.html 동작(무가드)         # 별도 jar 기동, ✅
2) 백엔드 다운 → 새로고침 시 ConnectionLost "서버에 연결할 수 없습니다" 차단화면  # 서버 kill 후, ⚠️
3) 401/크래시 편집 유실 복구: 편집 중 세션 만료 → 재로그인 후 "미저장 변경 N건 복구"  # 세션 만료 유도, 🔧
4) 공유 본문 XSS 하드닝: 스크립트 포함 노트를 공유 열람 → 실행 안 됨(이스케이프)     # 악성 본문 픽스처, ⚠️
```
**자동화 노트**: 1 ✅(모드만 바꿔 재기동) · 2 ⚠️(서버 종료 타이밍) · 3 🔧(세션 만료 인위 유도) · 4 ⚠️(픽스처 필요).

---

## 커버리지 매트릭스 요약

| 영역 | 브라우저 ✅ | 보조 ⚠️ | API/단위 🔧 |
|---|---|---|---|
| 인증/프로필/비번 | 로그인·가입·프로필·비번 | — | 멀티세션 무효화 |
| 에디터/노트 | 생성·rename·툴바·아웃라인·검색·삭제 | 디바운스 저장·내보내기 | — |
| 트리 이동 | 컨텍스트 메뉴·노출 경고 | — | **DnD 네이티브 드래그** |
| 휴지통 | 삭제·복구·purge | — | 30일 자동 purge |
| 공유 링크 | 생성·열람·취소·admin목록 | pin/maxViews/만료 | deny 우회 read |
| 관리자 9스크린 | 전부 폼/테이블 | — | 초기화 세션 무효 |
| 권한 | 트리 필터·403 | — | deny-sticky·합성·자동 exclude |
| 설정/테마 | 토글·단축키 | — | — |
| 복원력 엣지 | local 모드 | 다운 차단·XSS | 편집 유실 복구 |

## 권장 실행 순서
1. **Batch 1·2·3·9** (인증·프로필·에디터·설정) — 픽스처 불필요, 가장 빠른 스모크
2. **Batch 7** (관리자) — 이후 권한/공유 픽스처를 admin UI로 생성 가능
3. **Batch 4·5·6·8** (이동·휴지통·공유·권한) — 픽스처(0.2) 선행
4. **Batch 10** (엣지) — 환경 조작이라 마지막, 수동 보조

## 자동화 불가 항목(🔧)은 무엇으로 보증되나
- DnD 드롭 판정 → `frontend/src/lib/dnd.test.ts`(9 케이스)
- 멀티세션 비번 무효화 → `backend ChangePasswordApiTest`
- 30일 purge → 스케줄러 단위테스트(`WORKNOTE_PURGE_RETENTION_DAYS`)
- deny-sticky·권한 합성·자동 exclude → 백엔드 권한 엔진 단위테스트
- 편집 유실 복구 → `pendingStore.test.ts` + `useVaultSync` 로직
