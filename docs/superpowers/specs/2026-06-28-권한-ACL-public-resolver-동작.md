# 권한 해석기 — 직접 ACL ↔ public 관계 (구현 동작)

> 작성 2026-06-28. **실제 코드 기준** 레퍼런스(설계 의도는 `2026-06-10-worknote-권한-디렉토리-design.md` §5 참조).
> 코드: `backend/src/main/java/com/worknote/acl/` (`AclResolver`·`PermissionService`·`Access`), 스키마 `db/migration/sqlite/V2__phase2_auth_acl.sql`.

## 1. 두 메커니즘은 별개다

| | 직접 ACL (`acl` 테이블) | 공개 Public (`public_flag` 테이블) |
|---|---|---|
| 대상 | 주체별 grant — `user:<id>` / `team:<id>` / `all:@all` | 노드별 모드 — `public` / `exclude` |
| 값 | `read` / `edit` / `deny` | `public`(공개) / `exclude`(제외) |
| 성격 | read·edit·차단 전부 | **read 전용** |
| PK | (principal_type, principal_id, node_id) | node_id (노드당 1개) |

- `all:@all` = "전체 사용자" **ACL 주체**. public_flag와 다르다 — `all` deny는 edit·read 모두 차단, public은 read만 부여.
- 둘 다 **조상 체인 상속**(인접 리스트 `parent_id` walk)으로 해석한다.

## 2. 순수 해석 함수 (`AclResolver`)

- **`nearestExplicit(chain, grantsByNode)`** — 한 주체의 grant 1개로 축약.
  체인(노드→루트)에 그 주체의 `deny`가 **하나라도** 있으면 더 가까운 allow와 무관하게 `"deny"` 반환(= **deny-sticky**, §5.1 "deny 아래 재허용 없음"). deny 없으면 가장 가까운 명시 grant, 없으면 `null`.
- **`combine(nearestGrants)`** — 다중 주체(개인+팀들+all) **deny-우선 합집합**.
  하나라도 `deny` → `Access.DENY`(즉시). 아니면 `edit` 있으면 `EDIT`, `read` 있으면 `READ`, 없으면 `NONE`.
- **`publicRead(chain, flagsByNode)`** — 체인에서 **가장 가까운** public_flag가 `public`이면 true, `exclude`가 더 가깝거나 없으면 false.

`Access` enum = `NONE / READ / EDIT / DENY`. **ordinal 비교 금지** — `allowsRead()`(READ·EDIT)·`allowsEdit()`(EDIT)만 사용.

## 3. 직접 ACL ↔ public 관계 = `canRead`의 평가 순서

read 판정의 핵심 (`PermissionService.canRead`):

```java
if (isAdmin(user)) return true;                 // (0) 관리자는 ACL·deny·public 전부 우회
Access access = resolveAcl(user, chain);         // 직접 ACL(user+teams+all) deny-우선 합산
if (access == Access.DENY) return false;          // (1) ACL deny → 즉시 차단, public 미조회
if (access.allowsRead()) return roleHas(user,"res.read"); // (2) ACL이 read/edit 부여 → 허용
return publicRead(chain, flags) && roleHas(user,"res.read"); // (3) ACL이 NONE일 때만 public 폴백
```

→ **관계 한 줄 요약: public은 직접 ACL이 침묵(NONE)할 때만 작동하는 read 폴백이다.**

| 직접 ACL 결과 | public 설정 | 최종 read |
|---|---|---|
| `deny` | 무관 | **차단** (public을 보지도 않음) |
| `read`/`edit` | 무관 | 허용 (res.read 보유 시) |
| `NONE`(명시 grant 없음) | `public` | 허용 (res.read 보유 시) |
| `NONE` | `exclude`/미설정 | 차단 |

`res.read`는 **역할 상한** — 유효 권한 = 역할 caps ∩ ACL/public 범위. public이라도 역할에 `res.read`가 없으면 못 읽는다.

## 4. edit 과 public

`canEdit`는 public_flag를 **참조하지 않는다**(public은 read 전용):

```java
Access access = resolveAcl(user, chain);
return access != Access.DENY && access.allowsEdit() && roleHas(user, "res.edit");
```

edit 권한은 오직 직접 ACL `edit` grant(+역할 `res.edit`)로만 생긴다. 폴더를 public으로 해도 쓰기는 안 열린다.

## 5. deny-sticky (조상 deny 고정)

- 단건 경로(`canRead`/`canEdit`): `nearestExplicit`가 체인 어디든 deny 발견 시 `"deny"`.
- 트리 필터(`readableIds`→`walk`, top-down): 부모에서 이미 `deny`인 주체는 더 깊은 allow를 **무시**(copy-on-write로 nearest 갱신 시 `!"deny".equals(nearest.get(principal))` 가드). 같은 주체 안에서 deny 아래 재허용 불가.
- 합집합 단계에서도 한 주체라도 deny면 전체 DENY → 다른 주체(개인 grant 등)가 못 뒤집음.

## 6. 새 노트 기본 제외 (`VaultService.create`)

```java
if (isNote && parentId != null && isPubliclyVisible(parentId))
    aclMapper.insertPublicFlag(id, "exclude");   // 명시 exclude로 박제 (스펙 §7)
```

- **public 폴더 하위에 새 노트를 만들면** 자동으로 `exclude` flag를 박는다 → 폴더가 공개여도 새 노트는 비공개가 기본.
- 공개하려면 관리자가 그 노트의 flag를 `public`으로 바꿔야 한다(또는 exclude 삭제 후 폴더 public 상속).
- public 아닌 폴더 하위 새 노트는 flag를 안 박는다(어차피 비공개).

## 7. 우회·경계

- **관리자**(역할 caps가 `admin.users/permissions/roles/security/audit` 5종 전부): `canRead`/`canEdit` 최상단에서 `true` — ACL·deny·public 전부 우회.
- **local 모드**(`worknote.mode≠server`): `user==null` → 전체 허용(무인증). server 모드의 `user==null`은 방어적 차단.
- **미존재/null 노드**: 조상 체인 비면 default-deny(`canRead`/`canEdit` 대칭).
- **공유 링크**: 이 해석기 밖. deny를 넘는 유일한 read 예외(만료·취소·로깅, read 전용) — `canRead` 주석 "공유 링크는 다음 계획"대로 별도 경로.

## 8. 우선순위 종합 (read 기준, 높음 → 낮음)

```
관리자/ local 모드             (전부 우회 → 허용)
  > 조상 deny(sticky) / 직접 deny / all:@all deny   (차단, public 무시)
  > 직접 read·edit grant (user/team/all)            (허용, res.read 필요)
  > public_flag = public (가장 가까운)              (허용, res.read 필요)   ← ACL이 NONE일 때만
  > 그 외(NONE + exclude/미설정)                    (차단, default-deny)
[별도 통로] 공유 링크 = deny를 넘는 read 예외
```

## 9. 코드 위치

| 관심사 | 파일 |
|---|---|
| 순수 알고리즘(nearestExplicit·combine·publicRead) | `acl/AclResolver.java` |
| 합산 결과 enum | `acl/Access.java` |
| 단건 read/edit·트리 필터·deny-sticky walk | `acl/PermissionService.java` |
| 새 노트 자동 exclude | `vault/VaultService.java` (`create`, `isPubliclyVisible`) |
| 스키마(acl·public_flag CHECK) | `db/migration/sqlite/V2__phase2_auth_acl.sql` |
