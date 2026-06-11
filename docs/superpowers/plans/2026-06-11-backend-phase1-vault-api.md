# Backend Phase 1 — Vault API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단일 실행 jar(정적 frontend 서빙 + 노드 단위 REST API + SQLite)를 구축하고, frontend를 localStorage에서 HTTP 동기화로 전환한다. 1단계 = 단일 사용자·무권한 (권한 엔진은 2단계).

**Architecture:** Spring Boot 3.5(Java 21) + MyBatis + Flyway(vendor-aware) + sqlite-jdbc. 트리는 인접 리스트(parent_id) — 서브트리 연산은 재귀 CTE. 컨트롤러→서비스→매퍼 3계층, 도메인은 record. frontend는 `VaultApi` 클라이언트 + 액션 동기화 레이어(reducer 액션 → API 호출, content는 디바운스 PATCH)로 연동. 배포 = `java -jar worknote.jar` 하나.

**Tech Stack:** Java 21 · Spring Boot 3.5.x · Gradle(Groovy DSL) · MyBatis(mybatis-spring-boot-starter 3.x) · Flyway · sqlite-jdbc(org.xerial) · JUnit 5

**Oracle 마이그레이션 대비 규칙 (전 태스크 공통):**
1. 매퍼 SQL은 ANSI 지향 — SQLite 전용 함수·PRAGMA를 SQL에 넣지 않는다 (연결 설정은 datasource 레벨에서).
2. `BOOLEAN` 컬럼 금지(Oracle 비호환) — 현 스키마엔 없음. 날짜는 ISO-8601 TEXT(이식 단순) — Oracle 전환 시 TIMESTAMP 변환은 마이그레이션 스크립트 몫.
3. Flyway 위치 = `classpath:db/migration/{vendor}` — 지금은 `sqlite/`만, Oracle 추가 시 `oracle/` 디렉토리만 더한다.
4. 재귀 CTE는 SQLite `WITH RECURSIVE` / Oracle은 `WITH`(RECURSIVE 키워드 불가) — **CTE 쿼리는 매퍼 XML에 격리**하고 주석으로 Oracle 변환 포인트 표기.

**스펙 근거:** `docs/superpowers/specs/2026-06-10-worknote-권한-디렉토리-design.md` §8.1 (node/tag 스키마 — 1·2단계 공통), §4.3 (라이프사이클: rename 무영향·move 가드·휴지통 30일). 1단계는 권한 테이블·해석기 없음(§0).

**API 표면 (전 태스크 공통 참조):**

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/api/tree` | 활성(비휴지통) 전체 트리 — 중첩 JSON |
| POST | `/api/nodes` | 노드 생성 (클라이언트 id 허용) |
| PATCH | `/api/nodes/{id}` | name/content/tags 부분 수정 (+updated_at 서버 스탬프) |
| POST | `/api/nodes/{id}/move` | parent 변경 (사이클 검증) |
| DELETE | `/api/nodes/{id}` | 휴지통 (하위째 soft-delete) |
| GET | `/api/trash` | 휴지통 목록 (삭제 루트만) |
| POST | `/api/trash/{id}/restore` | 복구 (하위째) |
| DELETE | `/api/trash/{id}` | 영구 삭제 (purge, 하위+tag 포함) |
| GET | `/api/health` | `{status:"ok"}` |

JSON 노드 형태는 frontend `types.ts`와 동일: `{id, type:"folder"|"note", name|title, position, children?, tags?, updated?, content?}` — folder는 name/children, note는 title/tags/updated/content. (서버 내부는 name 단일 컬럼, 직렬화에서 note일 때 title로 노출)

**의도된 단순화 (YAGNI — 스펙에 없거나 frontend가 아직 안 씀):**
- 형제 간 임의 재정렬 API 없음 — position은 생성/이동 시 `max+1` (frontend에 드래그 정렬 없음)
- 인증·CORS 없음 — 1단계 단일 사용자, dev는 Vite proxy로 동일 오리진
- 30일 자동 purge 스케줄러 없음 — 수동 purge 엔드포인트만 (2단계에서 추가)

---

## Task 0: Gradle + Spring Boot 스캐폴드

**Files:**
- Create: `backend/build.gradle`, `backend/settings.gradle`, `backend/gradle.properties`, `backend/src/main/java/com/worknote/WorknoteApplication.java`, `backend/src/main/resources/application.yml`, `backend/src/test/java/com/worknote/WorknoteApplicationTests.java`
- Modify: `backend/README.md` (스택 확정 반영)

- [ ] **Step 1: Gradle 스캐폴드 작성**

```groovy
// backend/build.gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.0'
    id 'io.spring.dependency-management' version '1.1.7'
}
group = 'com.worknote'
version = '0.1.0'
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.xerial:sqlite-jdbc:3.46.0.0'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter-test:3.0.4'
}
tasks.named('test') { useJUnitPlatform() }
```
(버전은 빌드 시점 최신 패치로 조정 가능 — 메이저는 고정. Spring Boot 3.5.x가 Java 21 공식 지원)

```groovy
// backend/settings.gradle
rootProject.name = 'worknote'
```

- [ ] **Step 2: 메인 클래스 + application.yml**

```java
// backend/src/main/java/com/worknote/WorknoteApplication.java
package com.worknote;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorknoteApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorknoteApplication.class, args);
    }
}
```

```yaml
# backend/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:sqlite:${WORKNOTE_DB:./worknote.db}
    driver-class-name: org.sqlite.JDBC
  flyway:
    locations: classpath:db/migration/sqlite   # Oracle 전환 시 vendor 디렉토리 추가
mybatis:
  mapper-locations: classpath:mappers/*.xml
  configuration:
    map-underscore-to-camel-case: true
server:
  port: 8080
```

- [ ] **Step 3: 부팅 스모크 테스트**

```java
// backend/src/test/java/com/worknote/WorknoteApplicationTests.java
package com.worknote;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class WorknoteApplicationTests {
    @Test
    void contextLoads() {}
}
```
주의: 인메모리 SQLite는 커넥션이 닫히면 사라짐 — 테스트 프로퍼티에 `cache=shared` 필수. Flyway 마이그레이션이 아직 없으므로 이 시점엔 빈 locations 경고만 — Step 4에서 빈 placeholder 마이그레이션 디렉토리 생성으로 해소(`db/migration/sqlite/.gitkeep`).

- [ ] **Step 4:** `cd backend && ./gradlew test` (gradle wrapper 생성: `gradle wrapper --gradle-version 8.14` — gradle 미설치면 brew install gradle 후) Expected: BUILD SUCCESSFUL
- [ ] **Step 5: Commit** — `chore(backend): spring boot 3.5 + mybatis + flyway + sqlite scaffold`

### Task 1: Flyway V1 스키마

**Files:**
- Create: `backend/src/main/resources/db/migration/sqlite/V1__init_vault.sql`
- Test: `backend/src/test/java/com/worknote/SchemaMigrationTest.java`

- [ ] **Step 1: V1 마이그레이션** — 스펙 §8.1 그대로:

```sql
-- V1__init_vault.sql  (ANSI 지향 — Oracle 전환 시 TEXT→VARCHAR2/CLOB, 스크립트는 db/migration/oracle에 별도 작성)
CREATE TABLE node (
  id         TEXT PRIMARY KEY,
  parent_id  TEXT REFERENCES node(id),
  type       TEXT NOT NULL CHECK (type IN ('folder','note')),
  name       TEXT NOT NULL,
  position   INTEGER NOT NULL,
  content    TEXT,
  updated_at TEXT,
  deleted_at TEXT,
  deleted_by TEXT
);
CREATE INDEX idx_node_parent ON node(parent_id);
CREATE INDEX idx_node_deleted ON node(deleted_at);

CREATE TABLE tag (
  node_id TEXT NOT NULL REFERENCES node(id),
  tag     TEXT NOT NULL,
  PRIMARY KEY (node_id, tag)
);
```

- [ ] **Step 2: 마이그레이션 검증 테스트**

```java
// backend/src/test/java/com/worknote/SchemaMigrationTest.java
package com.worknote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class SchemaMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void nodeAndTagTablesExist() {
        var tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('node','tag')", String.class);
        assertThat(tables).containsExactlyInAnyOrder("node", "tag");
    }
}
```

- [ ] **Step 3:** `./gradlew test` Expected: PASS
- [ ] **Step 4: Commit** — `feat(backend): flyway V1 vault schema (node/tag, spec §8.1)`

### Task 2: 도메인 + NodeMapper (MyBatis, TDD)

**Files:**
- Create: `backend/src/main/java/com/worknote/vault/NodeRow.java`, `backend/src/main/java/com/worknote/vault/NodeMapper.java`, `backend/src/main/resources/mappers/NodeMapper.xml`
- Test: `backend/src/test/java/com/worknote/vault/NodeMapperTest.java`

- [ ] **Step 1: 도메인 record**

```java
// backend/src/main/java/com/worknote/vault/NodeRow.java
package com.worknote.vault;

/** node 테이블 1행. tree 조립은 서비스 계층에서. */
public record NodeRow(
    String id, String parentId, String type, String name,
    int position, String content, String updatedAt,
    String deletedAt, String deletedBy
) {}
```

- [ ] **Step 2: 실패 테스트 작성** — 매퍼 시그니처 전부:

```java
// backend/src/test/java/com/worknote/vault/NodeMapperTest.java
package com.worknote.vault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class NodeMapperTest {
    @Autowired NodeMapper mapper;
    @Autowired JdbcTemplate jdbc;

    NodeRow folder(String id, String parentId) { return new NodeRow(id, parentId, "folder", "F-" + id, 1, null, null, null, null); }
    NodeRow note(String id, String parentId)   { return new NodeRow(id, parentId, "note", "N-" + id, 1, "body", "2026-06-11", null, null); }

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM tag"); jdbc.update("DELETE FROM node"); }

    @Test
    void insertAndFindById() {
        mapper.insert(folder("f1", null));
        assertThat(mapper.findById("f1")).isNotNull();
        assertThat(mapper.findById("f1").type()).isEqualTo("folder");
    }

    @Test
    void findActiveReturnsOnlyNonDeleted() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.softDeleteSubtree("n1", "2026-06-11T10:00:00", "me");
        assertThat(mapper.findActive()).extracting(NodeRow::id).containsExactly("f1");
    }

    @Test
    void subtreeIdsCollectsDescendants() {
        mapper.insert(folder("f1", null));
        mapper.insert(folder("f2", "f1"));
        mapper.insert(note("n1", "f2"));
        assertThat(mapper.subtreeIds("f1")).containsExactlyInAnyOrder("f1", "f2", "n1");
    }

    @Test
    void softDeleteAndRestoreSubtree() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.softDeleteSubtree("f1", "2026-06-11T10:00:00", "me");
        assertThat(mapper.findActive()).isEmpty();
        assertThat(mapper.findTrashRoots()).extracting(NodeRow::id).containsExactly("f1");
        mapper.restoreSubtree("f1");
        assertThat(mapper.findActive()).hasSize(2);
    }

    @Test
    void purgeSubtreeDeletesRowsAndTags() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.replaceTags("n1", java.util.List.of("a", "b"));
        mapper.softDeleteSubtree("f1", "2026-06-11T10:00:00", "me");
        mapper.purgeSubtree("f1");
        assertThat(mapper.findById("f1")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag", Integer.class)).isZero();
    }

    @Test
    void updateFieldsAndMove() {
        mapper.insert(folder("f1", null));
        mapper.insert(folder("f2", null));
        mapper.insert(note("n1", "f1"));
        mapper.updateFields("n1", "renamed", "new body", "2026-06-11T11:00:00");
        assertThat(mapper.findById("n1").name()).isEqualTo("renamed");
        mapper.move("n1", "f2", 5);
        assertThat(mapper.findById("n1").parentId()).isEqualTo("f2");
    }

    @Test
    void maxPositionAmongSiblings() {
        mapper.insert(folder("f1", null));
        assertThat(mapper.maxPosition("f1")).isZero();   // 자식 없음 → 0
        mapper.insert(note("n1", "f1"));
        assertThat(mapper.maxPosition("f1")).isEqualTo(1);
    }

    @Test
    void tagsRoundTrip() {
        mapper.insert(note("n1", null));
        mapper.replaceTags("n1", java.util.List.of("운영", "flow"));
        assertThat(mapper.findTags("n1")).containsExactlyInAnyOrder("운영", "flow");
        mapper.replaceTags("n1", java.util.List.of());
        assertThat(mapper.findTags("n1")).isEmpty();
    }
}
```

`./gradlew test` → FAIL (NodeMapper 없음) 확인.

- [ ] **Step 3: NodeMapper 인터페이스**

```java
// backend/src/main/java/com/worknote/vault/NodeMapper.java
package com.worknote.vault;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface NodeMapper {
    void insert(NodeRow row);
    NodeRow findById(@Param("id") String id);
    List<NodeRow> findActive();                                   // deleted_at IS NULL 전체 (트리 조립용)
    List<String> subtreeIds(@Param("id") String id);              // 재귀 CTE — 자신 포함 자손 id
    int maxPosition(@Param("parentId") String parentId);          // 형제 최대 position (없으면 0)
    void updateFields(@Param("id") String id, @Param("name") String name,
                      @Param("content") String content, @Param("updatedAt") String updatedAt);
    void move(@Param("id") String id, @Param("parentId") String parentId, @Param("position") int position);
    void softDeleteSubtree(@Param("id") String id, @Param("deletedAt") String deletedAt, @Param("deletedBy") String deletedBy);
    void restoreSubtree(@Param("id") String id);
    void purgeSubtree(@Param("id") String id);
    List<NodeRow> findTrashRoots();                               // 삭제됐지만 부모는 비삭제(또는 무부모)인 루트만
    List<String> findTags(@Param("nodeId") String nodeId);
    void replaceTags(@Param("nodeId") String nodeId, @Param("tags") List<String> tags);
}
```
(replaceTags는 default 메서드 불가 — XML에서 delete+insert 2문 처리하거나 서비스에서 deleteTags+insertTag 분리. **XML에 deleteTags/insertTag 2개로 정의하고 인터페이스도 그렇게 나눠도 됨** — 구현 시 택일하되 테스트는 위 시그니처 기준이므로 인터페이스에 default로 조합:)

```java
    // 인터페이스에 추가 (XML은 deleteTags/insertTag만)
    void deleteTags(@Param("nodeId") String nodeId);
    void insertTag(@Param("nodeId") String nodeId, @Param("tag") String tag);
    default void replaceTags(String nodeId, List<String> tags) {
        deleteTags(nodeId);
        for (String t : tags) insertTag(nodeId, t);
    }
```

- [ ] **Step 4: NodeMapper.xml** — 핵심 SQL (전부 ANSI 지향):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.worknote.vault.NodeMapper">

  <insert id="insert">
    INSERT INTO node (id, parent_id, type, name, position, content, updated_at, deleted_at, deleted_by)
    VALUES (#{id}, #{parentId}, #{type}, #{name}, #{position}, #{content}, #{updatedAt}, #{deletedAt}, #{deletedBy})
  </insert>

  <select id="findById" resultType="com.worknote.vault.NodeRow">
    SELECT * FROM node WHERE id = #{id}
  </select>

  <select id="findActive" resultType="com.worknote.vault.NodeRow">
    SELECT * FROM node WHERE deleted_at IS NULL ORDER BY parent_id, position, id
  </select>

  <!-- 재귀 CTE: Oracle 전환 시 'WITH RECURSIVE' → 'WITH' 로만 변경 (구조 동일) -->
  <select id="subtreeIds" resultType="string">
    WITH RECURSIVE sub(id) AS (
      SELECT id FROM node WHERE id = #{id}
      UNION ALL
      SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
    )
    SELECT id FROM sub
  </select>

  <select id="maxPosition" resultType="int">
    SELECT COALESCE(MAX(position), 0) FROM node
    WHERE deleted_at IS NULL
      AND <if test="parentId != null">parent_id = #{parentId}</if>
          <if test="parentId == null">parent_id IS NULL</if>
  </select>

  <update id="updateFields">
    UPDATE node SET
      name = COALESCE(#{name}, name),
      content = CASE WHEN #{content} IS NULL THEN content ELSE #{content} END,
      updated_at = #{updatedAt}
    WHERE id = #{id}
  </update>

  <update id="move">
    UPDATE node SET parent_id = #{parentId}, position = #{position} WHERE id = #{id}
  </update>

  <update id="softDeleteSubtree">
    UPDATE node SET deleted_at = #{deletedAt}, deleted_by = #{deletedBy}
    WHERE deleted_at IS NULL AND id IN (
      WITH RECURSIVE sub(id) AS (
        SELECT id FROM node WHERE id = #{id}
        UNION ALL SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
      ) SELECT id FROM sub
    )
  </update>

  <update id="restoreSubtree">
    UPDATE node SET deleted_at = NULL, deleted_by = NULL
    WHERE id IN (
      WITH RECURSIVE sub(id) AS (
        SELECT id FROM node WHERE id = #{id}
        UNION ALL SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
      ) SELECT id FROM sub
    )
  </update>

  <delete id="purgeSubtree">
    DELETE FROM node WHERE id IN (
      WITH RECURSIVE sub(id) AS (
        SELECT id FROM node WHERE id = #{id}
        UNION ALL SELECT n.id FROM node n JOIN sub s ON n.parent_id = s.id
      ) SELECT id FROM sub
    )
  </delete>
  <!-- purge 시 tag 정리는 서비스에서 deleteTagsIn 호출 (아래) -->
  <delete id="deleteTagsIn">
    DELETE FROM tag WHERE node_id IN
    <foreach item="i" collection="ids" open="(" separator="," close=")">#{i}</foreach>
  </delete>

  <select id="findTrashRoots" resultType="com.worknote.vault.NodeRow">
    SELECT c.* FROM node c
    LEFT JOIN node p ON c.parent_id = p.id
    WHERE c.deleted_at IS NOT NULL AND (p.id IS NULL OR p.deleted_at IS NULL)
    ORDER BY c.deleted_at DESC
  </select>

  <select id="findTags" resultType="string">
    SELECT tag FROM tag WHERE node_id = #{nodeId} ORDER BY tag
  </select>
  <delete id="deleteTags">DELETE FROM tag WHERE node_id = #{nodeId}</delete>
  <insert id="insertTag">INSERT INTO tag (node_id, tag) VALUES (#{nodeId}, #{tag})</insert>

</mapper>
```
(purgeSubtree의 tag 정리: 인터페이스에 `deleteTagsIn(@Param("ids") List<String> ids)` 추가, 테스트의 purge 케이스가 강제함 — 서비스 없이 매퍼 레벨에서 통과시키려면 테스트에서 subtreeIds로 얻어 deleteTagsIn 후 purgeSubtree 순서 호출로 검증하거나, purge 테스트를 `deleteTagsIn(subtreeIds(id)); purgeSubtree(id);` 시퀀스로 작성. **테스트 코드를 그 시퀀스로 조정해도 좋다 — 매퍼는 단문 책임만.**)

- [ ] **Step 5:** `./gradlew test` Expected: 전체 PASS
- [ ] **Step 6: Commit** — `feat(backend): NodeMapper with recursive-CTE subtree ops (TDD)`

### Task 3: VaultService (트리 조립·검증·트랜잭션, TDD)

**Files:**
- Create: `backend/src/main/java/com/worknote/vault/VaultService.java`, `backend/src/main/java/com/worknote/vault/VaultNode.java`(트리 DTO), `backend/src/main/java/com/worknote/vault/VaultException.java`
- Test: `backend/src/test/java/com/worknote/vault/VaultServiceTest.java`

- [ ] **Step 1: 트리 DTO** — frontend types.ts와 동일 JSON이 나오는 형태:

```java
// backend/src/main/java/com/worknote/vault/VaultNode.java
package com.worknote.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultNode(
    String id, String type, String name, String title,   // folder→name, note→title (둘 중 하나만 non-null)
    Integer position, List<VaultNode> children,           // folder만
    List<String> tags, String updated, String content     // note만
) {}
```

- [ ] **Step 2: 실패 테스트** — 서비스 규칙 전부:

```java
// backend/src/test/java/com/worknote/vault/VaultServiceTest.java
package com.worknote.vault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class VaultServiceTest {
    @Autowired VaultService svc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.update("DELETE FROM tag"); jdbc.update("DELETE FROM node"); }

    @Test
    void createAssignsPositionAndBuildsTree() {
        svc.create("f1", null, "folder", "아키텍처", null);
        svc.create("n1", "f1", "note", "결제 파이프라인", "body");
        svc.create("n2", "f1", "note", "승인 시퀀스", "body2");
        List<VaultNode> tree = svc.tree();
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children()).extracting(VaultNode::title)
            .containsExactly("결제 파이프라인", "승인 시퀀스");   // position 순
    }

    @Test
    void createUnderNoteRejected() {
        svc.create("n1", null, "note", "노트", "");
        assertThatThrownBy(() -> svc.create("n2", "n1", "note", "자식", ""))
            .isInstanceOf(VaultException.class).hasMessageContaining("폴더가 아닙니다");
    }

    @Test
    void createWithMissingParentRejected() {
        assertThatThrownBy(() -> svc.create("n1", "ghost", "note", "x", ""))
            .isInstanceOf(VaultException.class);
    }

    @Test
    void duplicateIdRejected() {
        svc.create("n1", null, "note", "a", "");
        assertThatThrownBy(() -> svc.create("n1", null, "note", "b", ""))
            .isInstanceOf(VaultException.class).hasMessageContaining("이미 존재");
    }

    @Test
    void moveIntoOwnDescendantRejected() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("f2", "f1", "folder", "B", null);
        assertThatThrownBy(() -> svc.move("f1", "f2"))
            .isInstanceOf(VaultException.class).hasMessageContaining("하위로 이동");
    }

    @Test
    void moveToRootAndIntoFolder() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("n1", "f1", "note", "x", "");
        svc.move("n1", null);                       // 루트로
        assertThat(svc.tree()).extracting(VaultNode::id).contains("n1");
        svc.move("n1", "f1");                       // 다시 폴더로
        assertThat(svc.tree().get(0).children()).extracting(VaultNode::id).contains("n1");
    }

    @Test
    void updateStampsUpdatedAtAndReplacesTags() {
        svc.create("n1", null, "note", "x", "");
        svc.update("n1", "새 제목", "새 본문", List.of("운영"));
        VaultNode n = svc.tree().get(0);
        assertThat(n.title()).isEqualTo("새 제목");
        assertThat(n.tags()).containsExactly("운영");
        assertThat(n.updated()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void trashLifecycle() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("n1", "f1", "note", "x", "");
        svc.trash("f1", "S2019-0007");
        assertThat(svc.tree()).isEmpty();
        assertThat(svc.trashList()).extracting(VaultNode::id).containsExactly("f1");
        svc.restore("f1");
        assertThat(svc.tree()).hasSize(1);
        svc.trash("f1", "S2019-0007");
        svc.purge("f1");
        assertThat(svc.trashList()).isEmpty();
        assertThat(svc.tree()).isEmpty();
    }

    @Test
    void unknownIdThrowsNotFound() {
        assertThatThrownBy(() -> svc.trash("ghost", "me")).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.move("ghost", null)).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.update("ghost", "a", null, null)).isInstanceOf(VaultException.class);
    }
}
```

`./gradlew test` → FAIL 확인.

- [ ] **Step 3: VaultService 구현** — 규칙:
- `tree()`: `findActive()` 1쿼리 → Java에서 parent_id로 그룹핑해 중첩 조립 (N+1 금지). note의 tags는 `tag` 전체를 1쿼리로 읽어 머지 (`findAllTags(): List<{nodeId,tag}>` 매퍼 1개 추가 — Task 2 XML에 `SELECT node_id, tag FROM tag` 추가).
- `create(id, parentId, type, name, content)`: id 중복 409, parent 존재+folder 검증, position = `maxPosition(parentId)+1`, note면 updated_at = 오늘(ISO date). type 검증(folder|note).
- `update(id, name, content, tags)`: 존재 검증, `updateFields` + tags non-null이면 replaceTags. updated_at = 오늘.
- `move(id, newParentId)`: 존재 검증, newParent 존재+folder 검증(루트 null 허용), **사이클 검증 — `subtreeIds(id).contains(newParentId)`면 거부**, position = `maxPosition(newParentId)+1`.
- `trash(id, by)` / `restore(id)` / `purge(id)`: 존재 검증 후 매퍼 호출. purge는 `deleteTagsIn(subtreeIds(id))` → `purgeSubtree(id)` 순서. **purge는 휴지통에 있는 노드만 허용** (deleted_at IS NULL이면 거부 — 실수 방지).
- 전 메서드 `@Transactional`. `VaultException(status, message)` — 404/409/422 구분용 (`NOT_FOUND`/`CONFLICT`/`INVALID`).
- 시간은 `Clock` 주입 (테스트 가능성) — 기본 systemDefaultZone.

- [ ] **Step 4:** `./gradlew test` Expected: 전체 PASS
- [ ] **Step 5: Commit** — `feat(backend): VaultService — tree assembly, move-cycle guard, trash lifecycle (TDD)`

### Task 4: REST 컨트롤러 + 에러 매핑 (TDD: MockMvc)

**Files:**
- Create: `backend/src/main/java/com/worknote/vault/VaultController.java`, `backend/src/main/java/com/worknote/vault/dto/CreateNodeRequest.java`, `.../dto/UpdateNodeRequest.java`, `.../dto/MoveNodeRequest.java`, `backend/src/main/java/com/worknote/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/worknote/vault/VaultControllerTest.java`

- [ ] **Step 1: 실패 테스트** — MockMvc로 API 표면 전부:

```java
// 핵심 케이스 (전체는 표면 표의 9개 엔드포인트 × 정상/오류)
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
@AutoConfigureMockMvc
class VaultControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("DELETE FROM tag"); jdbc.update("DELETE FROM node"); }

    @Test void healthOk() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("ok"));
    }
    @Test void createThenTreeRoundTrip() throws Exception {
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
            .content("{\"id\":\"f1\",\"parentId\":null,\"type\":\"folder\",\"name\":\"아키텍처\"}"))
           .andExpect(status().isCreated());
        mvc.perform(post("/api/nodes").contentType(APPLICATION_JSON)
            .content("{\"id\":\"n1\",\"parentId\":\"f1\",\"type\":\"note\",\"name\":\"결제\",\"content\":\"body\"}"))
           .andExpect(status().isCreated());
        mvc.perform(get("/api/tree")).andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value("f1"))
           .andExpect(jsonPath("$[0].children[0].title").value("결제"))
           .andExpect(jsonPath("$[0].children[0].name").doesNotExist());  // note는 title만
    }
    @Test void patchUpdatesAndStamps() throws Exception { /* PATCH name/content/tags → 200, updated 존재 */ }
    @Test void moveCycleReturns422() throws Exception { /* f1>f2, f1을 f2로 move → 422 */ }
    @Test void deleteToTrashAndRestore() throws Exception { /* DELETE → tree에서 사라짐, /api/trash에 등장, restore → 복귀 */ }
    @Test void purgeOnlyFromTrash() throws Exception { /* 활성 노드 purge → 409 또는 422 */ }
    @Test void unknownIdIs404() throws Exception { /* PATCH /api/nodes/ghost → 404 */ }
    @Test void duplicateIdIs409() throws Exception { /* 같은 id 재생성 → 409 */ }
}
```
(주석 케이스도 실제 코드로 전부 작성 — 플랜 지면상 축약했으나 구현 시 8케이스 모두 채울 것. JSON 검증은 jsonPath로.)

- [ ] **Step 2: DTO + 컨트롤러 + 핸들러 구현**
- DTO: `CreateNodeRequest(id?, parentId, type, name, content?)` — id 없으면 서버가 UUID 생성(frontend는 항상 보냄), `@NotBlank type/name` 검증. `UpdateNodeRequest(name?, content?, tags?)`. `MoveNodeRequest(parentId)`.
- 컨트롤러: API 표면 표 그대로. POST 201 + 생성 노드 JSON, PATCH/move/restore 200, DELETE 204.
- `ApiExceptionHandler`: `@RestControllerAdvice` — VaultException.status → HTTP 매핑, body `{error: message}`. MethodArgumentNotValidException → 400.

- [ ] **Step 3:** `./gradlew test` Expected: PASS
- [ ] **Step 4: Commit** — `feat(backend): vault REST API with error mapping (TDD)`

### Task 5: 정적 frontend 서빙 + 단일 jar

**Files:**
- Modify: `backend/build.gradle` (frontend dist 복사 태스크), `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/worknote/WebConfig.java` (필요 시)

- [ ] **Step 1: Gradle 연동** — bootJar 전에 frontend 빌드 산출물 복사:

```groovy
// build.gradle에 추가
tasks.register('copyFrontend', Copy) {
    dependsOn ':frontendBuild'   // 또는 외부에서 pnpm build 선행을 전제로 단순 복사만:
    from "${rootDir}/../frontend/dist"
    into layout.buildDirectory.dir('resources/main/static')
}
// 단순화: frontend 빌드는 별도 수행(pnpm build)을 전제로, processResources가 dist를 끌어온다
processResources {
    from("${rootDir}/../frontend/dist") { into 'static' }
}
```
(첫 형태가 아니라 **processResources 블록 방식 채택** — gradle이 pnpm을 호출하지 않음. README에 "백엔드 jar 빌드 전 `cd frontend && pnpm build` 선행" 명시. 폐쇄망 CI 단순성 우선.)

- [ ] **Step 2: 라우팅 확인** — Spring Boot는 `classpath:/static`을 자동 서빙. `/` → index.html, `/login.html`, `/admin.html` 직접 매핑되므로 추가 설정 불필요(MPA — SPA fallback 불필요). `vite.config`의 `base:"./"` 덕에 에셋 상대경로 동작.

- [ ] **Step 3: 검증**
```bash
cd frontend && pnpm build && cd ../backend && ./gradlew bootJar
java -jar build/libs/worknote-0.1.0.jar &
for p in "" login.html admin.html api/health; do curl -s -o /dev/null -w "%{http_code} /$p\n" "http://localhost:8080/$p"; done
# Expected: 전부 200
```
- [ ] **Step 4: Commit** — `feat(backend): serve frontend dist from single jar`

### Task 6: Frontend — VaultApi 클라이언트 + 모드 스위치

**Files:**
- Create: `frontend/src/storage/VaultApi.ts`, `frontend/src/storage/HttpVaultRepository.ts`
- Modify: `frontend/vite.config.ts` (dev proxy), `frontend/src/state/useVault.ts`(주입 지점만), `frontend/src/main.tsx`(모드 결정)

- [ ] **Step 1: VaultApi — fetch 래퍼 (노드 단위)**

```ts
// frontend/src/storage/VaultApi.ts
import type { VaultTree, NoteNode } from "../types";

const BASE = "/api";
async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, { headers: { "Content-Type": "application/json" }, ...init });
  if (!res.ok) throw new Error((await res.json().catch(() => ({})) as { error?: string }).error ?? `HTTP ${res.status}`);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const VaultApi = {
  tree: () => req<VaultTree>("/tree"),
  create: (n: { id: string; parentId: string | null; type: "folder" | "note"; name: string; content?: string }) =>
    req("/nodes", { method: "POST", body: JSON.stringify(n) }),
  update: (id: string, patch: { name?: string; content?: string; tags?: string[] }) =>
    req(`/nodes/${id}`, { method: "PATCH", body: JSON.stringify(patch) }),
  move: (id: string, parentId: string | null) =>
    req(`/nodes/${id}/move`, { method: "POST", body: JSON.stringify({ parentId }) }),
  trash: (id: string) => req(`/nodes/${id}`, { method: "DELETE" }),
};
```

- [ ] **Step 2: HttpVaultRepository** — 기존 인터페이스 구현 (load만 실사용, save는 no-op — 쓰기는 액션 동기화가 담당):

```ts
// frontend/src/storage/HttpVaultRepository.ts
import type { VaultRepository } from "./VaultRepository";
import type { VaultTree } from "../types";
import { VaultApi } from "./VaultApi";

export class HttpVaultRepository implements VaultRepository {
  async load(): Promise<VaultTree | null> {
    const tree = await VaultApi.tree();
    return tree.length ? tree : null;          // 빈 서버 = 시드 부트스트랩 대상
  }
  async save(): Promise<void> { /* no-op — 노드 단위 동기화가 담당 */ }
}
```

- [ ] **Step 3: dev proxy + 모드 스위치**
```ts
// vite.config.ts server 블록 추가
server: { proxy: { "/api": "http://localhost:8080" } },
```
모드 결정 (`src/storage/index.ts` 신규): `export const repository: VaultRepository = import.meta.env.VITE_STORAGE === "http" ? new HttpVaultRepository() : new LocalStorageRepository();` — `pnpm dev` 기본 local, `VITE_STORAGE=http pnpm dev`/프로덕션 빌드(`.env.production`에 `VITE_STORAGE=http`)는 http. App에서 `useVault(repository)`로 주입.

- [ ] **Step 4:** `pnpm test && pnpm build` (25 PASS 유지) → Commit — `feat(frontend): VaultApi client + storage mode switch`

### Task 7: Frontend — 액션 동기화 (reducer 액션 → API)

**Files:**
- Create: `frontend/src/state/useVaultSync.ts`, `frontend/src/state/useVaultSync.test.ts`
- Modify: `frontend/src/App.tsx` (훅 연결 + 에러 토스트)

- [ ] **Step 1: 동기화 훅 설계 (테스트 먼저)** — useVault의 actions를 감싸 HTTP 모드에서 API 호출을 병행:

```ts
// frontend/src/state/useVaultSync.test.ts — 순수 매핑 함수를 분리해 테스트
import { describe, it, expect, vi } from "vitest";
import { syncAction } from "./useVaultSync";

describe("syncAction", () => {
  it("maps addNote to VaultApi.create", async () => {
    const api = { create: vi.fn().mockResolvedValue(undefined) };
    await syncAction(api as never, { kind: "create", node: { id: "n1", parentId: "f1", type: "note", name: "제목 없는 노트", content: "" } });
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ id: "n1", parentId: "f1" }));
  });
  it("maps rename to update(name)", async () => {
    const api = { update: vi.fn().mockResolvedValue(undefined) };
    await syncAction(api as never, { kind: "rename", id: "n1", name: "새 이름" });
    expect(api.update).toHaveBeenCalledWith("n1", { name: "새 이름" });
  });
  it("maps remove to trash", async () => {
    const api = { trash: vi.fn().mockResolvedValue(undefined) };
    await syncAction(api as never, { kind: "remove", id: "n1" });
    expect(api.trash).toHaveBeenCalledWith("n1");
  });
});
```

- [ ] **Step 2: 구현** — `syncAction(api, op)` 순수 함수 + `useVaultSync(actions, mode)` 훅:
- HTTP 모드일 때 actions를 데코레이트한 동일 시그니처 객체 반환 (커맨드 패턴): addNote/addFolder → create, rename → update(name), remove → trash, updateNote → **content/tags는 노트별 1.5초 디바운스 PATCH** (타이핑 폭주 방지 — 기존 5초 localStorage 디바운스보다 짧게, 서버는 노드 단위라 부담 적음), move는 2단계에서 UI가 생기면 연결(매핑만 준비).
- 실패 시 `toast("서버 동기화 실패: ...", "alert")` — 낙관적 UI 유지(로컬 상태는 이미 반영), 재시도는 다음 변경에 편승. 1단계 단일 사용자라 충돌 없음.
- local 모드면 actions 그대로 반환(기존 동작 무변경).
- **시드 부트스트랩**: HTTP 모드에서 `load()`가 null(빈 서버)이면 SEED를 노드 단위 create로 1회 업로드 (App의 ready effect에서 — README 등 시드가 서버에 영속).

- [ ] **Step 3:** `pnpm test`(신규 3개 포함 PASS) → Commit — `feat(frontend): action-level sync to vault API (debounced content patch)`

### Task 8: E2E 스모크 (jar + browse)

- [ ] **Step 1: 통합 기동**
```bash
cd frontend && pnpm build && cd ../backend && ./gradlew bootJar
java -jar build/libs/worknote-0.1.0.jar &   # :8080
```
- [ ] **Step 2: browse 도구로 검증** (컨트롤러 직접 수행 가능)
  - `http://localhost:8080/` 로드 → 시드 부트스트랩 → 사이드바 트리 렌더
  - 노트 제목 수정 → 2초 대기 → `curl /api/tree`에 반영 확인
  - 새 노트/폴더 생성·이름변경·삭제 → API 반영 확인
  - **jar 재시작 → 데이터 유지** (SQLite 파일 영속) — localStorage가 아닌 서버가 진실 공급원임을 확인
  - login.html/admin.html 200 + 렌더
- [ ] **Step 3: Commit** — `chore: backend phase 1 e2e verified`

### Task 9: 문서 갱신

**Files:**
- Modify: `backend/README.md` (실행·빌드·API 표·Oracle 전환 노트), 루트 `CLAUDE.md` (backend 구현 완료 반영, 명령어 추가), `frontend/README.md` (HTTP 모드 사용법)

- [ ] **Step 1:** backend README — `./gradlew bootRun`(dev) / `pnpm build → ./gradlew bootJar → java -jar`(배포) / API 표 / `WORKNOTE_DB` 환경변수 / Oracle 전환 체크리스트(Flyway vendor 디렉토리·CTE 키워드·TEXT 매핑)
- [ ] **Step 2:** `pnpm test`(frontend) + `./gradlew test`(backend) 최종 PASS 확인
- [ ] **Step 3: Commit** — `docs: backend phase 1 complete`

---

## Self-Review 노트

- **스펙 커버리지**: §8.1 스키마 전체(Task 1), §4.3 라이프사이클 중 rename(=PATCH name)·move 가드(사이클 — 노출 경고는 권한 없는 1단계에선 무의미라 제외)·휴지통/복구/purge(Task 2-4) 커버. 권한·감사·공유링크·팀은 2단계 — 의도적 비포함 (스펙 §0).
- **타입 일관성**: NodeRow(Task 2)→VaultNode(Task 3)→DTO(Task 4)→frontend types.ts(기존) 매핑이 단방향. folder=name/note=title 직렬화 규칙은 Task 3 DTO와 Task 4 jsonPath 테스트가 상호 검증.
- **알려진 트레이드오프**: ① 낙관적 UI + fire-and-forget 동기화(Task 7) — 1단계 단일 사용자 전제, 2단계에서 충돌 처리 필요 ② updated 스탬프가 클라이언트 표시용(로컬)과 서버 기록용으로 이원화 — 1단계 무해, 2단계에서 서버 단일화 ③ jar 빌드가 frontend 선빌드를 전제(processResources) — README에 명시.
