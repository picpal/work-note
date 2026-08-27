package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.worknote.auth.PasswordPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 브레이크글래스 센티넬의 경로 판정·출처 검증·파싱 — DB도 스프링도 없는 순수 로직만. */
class BreakGlassFileTest {

    private static final String ME = System.getProperty("user.name");

    @TempDir Path tmp;

    // ---- locate: 어디를 보는가 (오버라이드 없음 — DB 옆 한 곳뿐) ----

    @Test
    void locate_derivesSentinelNextToTheDbFile() {
        assertThat(BreakGlassFile.locate("jdbc:sqlite:/var/lib/worknote/worknote.db"))
            .isEqualTo(Paths.get("/var/lib/worknote/break-glass"));
    }

    /** 테스트가 전부 인메모리라 여기서 걸러진다 — 경로가 없으면 기능 자체가 비활성이다. */
    @Test
    void locate_inMemoryDbHasNoSentinel() {
        assertThat(BreakGlassFile.locate("jdbc:sqlite:file:memdb-x?mode=memory&cache=shared")).isNull();
        assertThat(BreakGlassFile.locate("jdbc:sqlite::memory:")).isNull();
    }

    @Test
    void locate_nonSqliteOrMissingUrlHasNoSentinel() {
        assertThat(BreakGlassFile.locate("jdbc:oracle:thin:@//host:1521/XE")).isNull();
        assertThat(BreakGlassFile.locate(null)).isNull();
    }

    /** 상대 경로 DB(local 모드 기본값 ./worknote.db)도 부모 디렉토리가 나와야 한다 — 절대화가 빠지면 parent가 null이다. */
    @Test
    void locate_relativeDbPathResolvesToAnAbsoluteParent() {
        Path sentinel = BreakGlassFile.locate("jdbc:sqlite:worknote.db");
        assertThat(sentinel).isNotNull();
        assertThat(sentinel.isAbsolute()).isTrue();
        assertThat(sentinel.getFileName()).hasToString("break-glass");
    }

    /**
     * 렉시컬 {@code normalize()}를 쓰지 않는다 — {@code StoragePermissions.hardenDb}와 같은 관례다.
     * {@code ..}가 심링크를 지나가면 렉시컬 해석과 OS 해석이 갈린다. 갈리면 운영자가 <b>실제 DB 옆</b>에 만든
     * 센티넬을 앱은 엉뚱한 디렉토리에서 찾아 조용히 무시하고, 반대로 그 렉시컬 경로에 남아 있던
     * 무관한 오래된 파일을 실행한다. 여기서 보는 건 "OS가 실제로 여는 DB의 디렉토리인가" 하나다.
     */
    @Test
    void locate_followsTheOsInterpretationOfDotDotThroughSymlinks() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path inner = Files.createDirectories(tmp.resolve("outer/inner"));
        Path realDbDir = Files.createDirectories(tmp.resolve("outer/db"));   // OS 해석이 도달하는 곳
        Files.createDirectories(tmp.resolve("db"));                          // 렉시컬 normalize가 도달하는 곳
        Files.createSymbolicLink(tmp.resolve("link"), inner);

        Path sentinel = BreakGlassFile.locate("jdbc:sqlite:" + tmp.resolve("link/../db/worknote.db"));

        assertThat(sentinel).isNotNull();
        assertThat(sentinel.getParent().toRealPath()).isEqualTo(realDbDir.toRealPath());
    }

    // ---- violation: 출처 검증(이 기능의 보안 논거 자체) ----
    //
    // 전제는 "700 앱 소유 디렉토리에 파일을 만들 수 있는 능력 = 이미 DB를 직접 고칠 수 있는 능력"이다.
    // 그 전제가 실제로 성립하는지 매번 확인한다 — 주장만 하고 검사하지 않으면 그냥 뒷문이다.

    private static BreakGlassFile.Provenance ok() {
        return new BreakGlassFile.Provenance(false, true, 40, ME,
            PosixFilePermissions.fromString("rw-------"), ME, PosixFilePermissions.fromString("rwx------"),
            List.of(ancestor("/srv", "rwxr-xr-x"), ancestor("/", "rwxr-xr-x")));
    }

    /** 정상 배포의 조상 — root 소유 755이고, 그 안에서 우리가 지나가는 엔트리는 앱 계정 것이다. */
    private static BreakGlassFile.Ancestor ancestor(String path, String mode) {
        return ancestor(path, "root", mode, false, ME);
    }

    private static BreakGlassFile.Ancestor ancestor(String path, String owner, String mode,
            boolean sticky, String entryOwner) {
        return new BreakGlassFile.Ancestor(path, owner, PosixFilePermissions.fromString(mode), sticky,
            "worknote", entryOwner);
    }

    @Test
    void violation_ownerOnlyFileInOwnerOnlyDirectoryIsAccepted() {
        assertThat(BreakGlassFile.violation(ok(), ME)).isNull();
        // 소유자 실행 비트나 더 좁은 400은 보안 성질을 해치지 않는다 — 그룹·타인 비트만 본다.
        assertThat(BreakGlassFile.violation(perms(ok(), "r--------"), ME)).isNull();
    }

    @Test
    void violation_rejectsGroupOrOtherReadableFile() {
        assertThat(BreakGlassFile.violation(perms(ok(), "rw-r--r--"), ME)).contains("600");
        assertThat(BreakGlassFile.violation(perms(ok(), "rw----r--"), ME)).contains("600");
        assertThat(BreakGlassFile.violation(perms(ok(), "rw--w----"), ME)).contains("600");
    }

    /**
     * 핵심 케이스 — WORKNOTE_STORAGE_STRICT=false로 그룹/타인 <b>쓰기 가능한</b> 부모(775·777)를 허용한
     * 배치에서, DB(600)를 못 읽는 다른 로컬 사용자가 센티넬을 만들어 관리자 계정을 가져갈 수 있었다.
     * 그건 "새 권한 없음"이 아니다.
     *
     * <p>755는 타인에게 {@code w}를 주지 않으므로 그 공격 자체는 성립하지 않지만, 부모에는 그룹/타인 비트를
     * 아예 요구하지 않는다 — 700은 값싸고 확실한 선이고, 여기서 넓히면 판정이 미묘해질 뿐 얻는 게 없다.
     */
    @Test
    void violation_rejectsPermissiveParentDirectory() {
        assertThat(BreakGlassFile.violation(parentPerms(ok(), "rwxrwxr-x"), ME)).contains("700");   // 775 = 실제 위협
        assertThat(BreakGlassFile.violation(parentPerms(ok(), "rwxrwxrwx"), ME)).contains("700");   // 777
        assertThat(BreakGlassFile.violation(parentPerms(ok(), "rwxr-xr-x"), ME)).contains("700");   // 755도 거부한다
    }

    /** 미리 심어둔 파일 — 나중에 chmod 700을 해도 파일의 소유자는 바뀌지 않는다. 그래서 소유자를 본다. */
    @Test
    void violation_rejectsFileOwnedBySomeoneElse() {
        assertThat(BreakGlassFile.violation(owner(ok(), "intruder"), ME)).contains("소유자");
    }

    @Test
    void violation_rejectsDirectoryOwnedBySomeoneElse() {
        assertThat(BreakGlassFile.violation(parentOwner(ok(), "intruder"), ME)).contains("소유자");
    }

    @Test
    void violation_rejectsSymlinkAndNonRegularFile() {
        assertThat(BreakGlassFile.violation(new BreakGlassFile.Provenance(true, false, 40, ME,
            PosixFilePermissions.fromString("rw-------"), ME,
            PosixFilePermissions.fromString("rwx------"), List.of()), ME)).contains("심볼릭");
        assertThat(BreakGlassFile.violation(new BreakGlassFile.Provenance(false, false, 40, ME,
            PosixFilePermissions.fromString("rw-------"), ME,
            PosixFilePermissions.fromString("rwx------"), List.of()), ME)).contains("일반 파일");
    }

    @Test
    void violation_rejectsOversizedFile() {
        assertThat(BreakGlassFile.violation(size(ok(), BreakGlassFile.MAX_BYTES + 1), ME)).contains("큽니다");
        assertThat(BreakGlassFile.violation(size(ok(), BreakGlassFile.MAX_BYTES), ME)).isNull();
    }

    /** 조상은 <b>쓰기 비트만</b> 본다 — 정상 배포의 /, /var, /var/lib, /srv는 755이고 그건 문제가 아니다. */
    @Test
    void violation_acceptsReadableButNotWritableAncestors() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/var/lib/worknote", "rwx------"), ancestor("/var/lib", "rwxr-xr-x"),
            ancestor("/var", "rwxr-xr-x"), ancestor("/", "rwxr-xr-x")), ME)).isNull();
    }

    /** 조상에 쓸 수 있으면 데이터 디렉토리를 엔트리째 바꿔치기할 수 있다 — 부모의 700이 근거가 되지 못한다. */
    @Test
    void violation_rejectsAWritableAncestor() {
        assertThat(BreakGlassFile.violation(ancestors(ok(), ancestor("/srv", "rwxrwxr-x")), ME))
            .contains("/srv").contains("쓰기");
        assertThat(BreakGlassFile.violation(ancestors(ok(), ancestor("/srv", "rwxrwxrwx")), ME))
            .contains("/srv");
        // 루트 쪽(먼 조상)이 열려 있어도 같다 — 체인 전체를 본다.
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/srv/worknote", "rwx------"), ancestor("/srv", "rwx------"),
            ancestor("/", "rwxrwxrwx")), ME)).contains("쓰기");
    }

    /**
     * 조상 소유자는 <b>앱 실행 계정 또는 root</b>만 허용한다. 쓰기 비트만 보면 부족하다 —
     * {@code /home/other/worknote}가 {@code other} 소유 755면 {@code other}는 자기 디렉토리의 엔트리를
     * 언제든 rename할 수 있고(쓰기 비트가 없어도 자기 디렉토리를 chmod할 수 있다), 그러면 데이터 디렉토리를
     * 통째로 바꿔치기해 "700 앱 소유 부모"인 척 만들 수 있다 = "새 권한이 생기지 않는다"는 전제가 깨진다.
     */
    @Test
    void violation_rejectsAnAncestorOwnedByAnotherLocalUser() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/home/other/worknote", "other", "rwxr-xr-x", false, ME)), ME))
            .contains("/home/other/worknote")   // 파일·부모 소유자 메시지와 섞이지 않도록 경로로 지목한다
            .contains("other")
            .contains("소유자");
    }

    /** 흔한 배치는 그대로 통과해야 한다 — {@code /}, {@code /var}, {@code /var/lib}는 root, 데이터 디렉토리는 앱 계정. */
    @Test
    void violation_acceptsAncestorsOwnedByTheAppAccountOrRoot() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/var/lib/worknote", ME, "rwx------", false, ME),
            ancestor("/var/lib", "root", "rwxr-xr-x", false, ME),
            ancestor("/var", "root", "rwxr-xr-x", false, ME),
            ancestor("/", "root", "rwxr-xr-x", false, ME)), ME)).isNull();
    }

    /**
     * sticky(1777)는 <b>조건부</b> 예외다. sticky는 "누구도 rename하지 못한다"가 아니라
     * "엔트리 소유자·디렉토리 소유자·특권자만 rename한다"이므로, 통과시키려면 그 디렉토리가 앱 계정·root 소유이고
     * <b>우리가 지나가는 엔트리도</b> 앱 계정(또는 root) 것이어야 한다.
     * {@code /tmp}(root 1777) 아래에 앱 소유 디렉토리를 두는 정상 배치가 이 형태다.
     */
    @Test
    void violation_acceptsAStickyWritableAncestorWhenTheEntryWeTraverseIsOurs() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/tmp", "root", "rwxrwxrwx", true, ME)), ME)).isNull();
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/tmp", "root", "rwxrwxrwx", true, "root")), ME)).isNull();
    }

    /**
     * 반대쪽 — {@code /tmp/link}가 공격자 소유 심링크라면 sticky가 있어도 공격자는 <b>자기 엔트리</b>를
     * 검증 직후 갈아끼울 수 있다. 그 배치는 통과시키면 안 된다.
     * (실파일로는 남의 소유 엔트리를 만들 수 없어 판정은 여기서만 본다 — 사실 채우기는 {@link #ancestorsAbove_recordsTheOwnerOfEachDirectoryAndOfTheEntryWeTraverse()})
     */
    @Test
    void violation_rejectsAStickyWritableAncestorWhenTheEntryWeTraverseIsSomeoneElses() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/tmp", "root", "rwxrwxrwx", true, "intruder")), ME)).contains("/tmp").contains("쓰기");
    }

    /** 엔트리 소유자를 알 수 없으면 예외를 인정하지 않는다 — 더 엄격한 쪽(sticky를 읽지 못할 때와 같은 결). */
    @Test
    void violation_stickyIsNoExcuseWhenTheTraversedEntryOwnerIsUnknown() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/tmp", "root", "rwxrwxrwx", true, null)), ME)).contains("/tmp");
    }

    /** sticky 디렉토리 자신이 남의 것이면 소유자 규칙에서 먼저 걸린다 — 디렉토리 소유자는 sticky와 무관하게 rename한다. */
    @Test
    void violation_rejectsAStickyDirectoryOwnedBySomeoneElse() {
        assertThat(BreakGlassFile.violation(ancestors(ok(),
            ancestor("/intruder-tmp", "intruder", "rwxrwxrwx", true, ME)), ME))
            .contains("/intruder-tmp").contains("소유자");
    }

    // ---- verifyProvenance: 실제 파일에서 위 사실들을 읽어오는가 ----

    @Test
    void verifyProvenance_acceptsAProperlyCreatedSentinel() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel600("emp=admin\n");
        BreakGlassFile.verifyProvenance(file);   // 예외 없음
    }

    @Test
    void verifyProvenance_rejectsGroupReadableSentinel() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel600("emp=admin\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("600");
    }

    /** 부모가 넓으면 파일이 아무리 600이어도 거절 — 누구나 그 자리에 파일을 놓을 수 있었다는 뜻이다. */
    @Test
    void verifyProvenance_rejectsSentinelInAPermissiveDirectory() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel600("emp=admin\n");
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"));
        try {
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("700");
        } finally {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** 링크는 따라가지 않는다 — 따라가면 검증한 대상과 읽는 대상이 달라진다(TOCTOU). */
    @Test
    void verifyProvenance_rejectsSymlink() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path target = sentinel600("emp=admin\n");
        Path link = tmp.resolve("break-glass-link");
        Files.createSymbolicLink(link, target);
        assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(link))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("심볼릭");
    }

    @Test
    void verifyProvenance_rejectsDirectory() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path dir = Files.createDirectory(tmp.resolve("break-glass"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("일반 파일");
    }

    @Test
    void verifyProvenance_rejectsOversizedFile() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel600("emp=admin\n" + "#".repeat(BreakGlassFile.MAX_BYTES) + "\n");
        assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("큽니다");
    }

    // ---- 조상 체인: 부모의 700은 그 위에서 통째로 바꿔치기당하는 것을 막지 못한다 ----
    //
    // 부모만 보면 전제가 깨진다. 부모가 700·앱 소유여도 그 위 어딘가가 그룹/타인 쓰기 가능하면,
    // 거기에 쓸 수 있는 사람이 디렉토리 엔트리째 rename해서 같은 이름의 자기 소유 700 디렉토리로
    // 바꿔치기할 수 있다. 자식의 권한은 부모 안에서 자기 엔트리가 rename되는 것을 막지 못한다.
    // 결과적으로 DB를 읽지도 못하던 사람이 관리자 비밀번호를 지정하게 된다.

    /** 정상 배포의 조상은 대개 755(/, /var, /var/lib, /srv)다 — 읽기·탐색만으론 엔트리를 바꿀 수 없으니 통과해야 한다. */
    @Test
    void verifyProvenance_acceptsTheOrdinaryDeploymentChain() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path outer = Files.createDirectories(tmp.resolve("srv"));
        Path data = Files.createDirectories(outer.resolve("worknote"));
        Path file = sentinel600(data, "emp=admin\n");
        Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwxr-xr-x"));   // 755 = 정상
        try {
            BreakGlassFile.verifyProvenance(file);   // 예외 없음
        } finally {
            Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void verifyProvenance_rejectsAGroupWritableAncestor() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path outer = Files.createDirectories(tmp.resolve("srv"));
        Path data = Files.createDirectories(outer.resolve("worknote"));
        Path file = sentinel600(data, "emp=admin\n");
        Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwxrwxr-x"));   // 775
        try {
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(real(outer));   // 메시지는 실경로를 지목한다 — chmod할 대상이 그것이다
        } finally {
            Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** 타인 쓰기(777)도 같다. 부모가 아무리 700이어도 그 위에서 통째로 갈아끼울 수 있으면 의미가 없다. */
    @Test
    void verifyProvenance_rejectsAWorldWritableAncestorEvenSeveralLevelsUp() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path top = Files.createDirectories(tmp.resolve("top"));
        Path mid = Files.createDirectories(top.resolve("mid"));
        Path data = Files.createDirectories(mid.resolve("worknote"));
        Path file = sentinel600(data, "emp=admin\n");
        Files.setPosixFilePermissions(top, PosixFilePermissions.fromString("rwxrwxrwx"));   // 777, 두 단계 위
        try {
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(real(top));
        } finally {
            Files.setPosixFilePermissions(top, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * sticky(/tmp의 1777)는 예외로 인정한다 — sticky 디렉토리에서는 자기가 소유하지 않은 엔트리를
     * rename·삭제할 수 없으므로 바로 위 시나리오가 성립하지 않는다. 인정하지 않으면 실제로 막는 것 없이
     * {@code /tmp} 아래 배치와 CI(리눅스 {@code java.io.tmpdir}=/tmp)를 통째로 기동 불가로 만든다.
     */
    @Test
    void verifyProvenance_acceptsAStickyWritableAncestorLikeTmp() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path outer = Files.createDirectories(tmp.resolve("tmpish"));
        Path data = Files.createDirectories(outer.resolve("worknote"));
        Path file = sentinel600(data, "emp=admin\n");
        assumeTrue(setSticky1777(outer), "raw mode를 설정할 수 없는 환경 — skip");
        try {
            BreakGlassFile.verifyProvenance(file);   // 예외 없음
        } finally {
            Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * 중첩 심링크 — 렉시컬 부모 체인과 <b>최종</b> 실경로 체인 둘만 훑으면 중간 타깃의 조상이 통째로 빠진다.
     * <pre>
     * safe/l1   -> open/a          렉시컬 체인은 여기서 open/a 로 점프한다 — open 은 보지 않는다
     * open/a/l2 -> secure/final    최종 실경로 체인은 secure/... 만 올라간다
     * DB 부모   = safe/l1/l2/data  (앱 소유 700)
     * </pre>
     * {@code open}이 777이면 검증 후 {@code open/a}를 자기 트리로 바꿔치기할 수 있고, 그 뒤의 읽기·rename이
     * 공격자 파일을 집는다. 커널이 실제로 지나가는 디렉토리는 전부 봐야 한다.
     */
    @Test
    void verifyProvenance_rejectsAWritableAncestorOfAnIntermediateSymlinkTarget() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path open = Files.createDirectories(tmp.resolve("open/a"));
        Path data = Files.createDirectories(tmp.resolve("secure/final/data"));
        Files.createDirectories(tmp.resolve("safe"));
        Files.createSymbolicLink(tmp.resolve("safe/l1"), open);
        Files.createSymbolicLink(open.resolve("l2"), tmp.resolve("secure/final"));
        sentinel600(data, "emp=admin\n");
        Path viaLinks = tmp.resolve("safe/l1/l2/data/break-glass");
        Files.setPosixFilePermissions(tmp.resolve("open"), PosixFilePermissions.fromString("rwxrwxrwx"));
        try {
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(viaLinks))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(real(tmp.resolve("open")));
        } finally {
            Files.setPosixFilePermissions(tmp.resolve("open"), PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * 순환 심링크에서 <b>돌지 않는다</b>. 무한 루프는 기동 행 = 복구 기능에서 최악의 실패다.
     * 상한을 없애면 이 테스트는 실패가 아니라 영원히 끝나지 않으므로 {@link Timeout}으로 잡는다.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // 별도 스레드 — 같은 스레드면 무한 루프를 못 끊는다
    void verifyProvenance_failsClosedOnASymlinkCycleInsteadOfSpinningForever() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Files.createSymbolicLink(tmp.resolve("loop1"), tmp.resolve("loop2"));
        Files.createSymbolicLink(tmp.resolve("loop2"), tmp.resolve("loop1"));

        assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(tmp.resolve("loop1/data/break-glass")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("심링크");   // OS의 ELOOP가 아니라 우리 상한이 먼저 잡았다는 뜻
    }

    /**
     * 실측 쪽 — 판정({@link BreakGlassFile#violation})이 쓰는 사실을 실제로 채우는지. 셋을 본다:
     * (1) 커널이 지나가는 디렉토리를 전부 담는가(중간 심링크 타깃의 조상 포함),
     * (2) 각 디렉토리의 소유자를 담는가, (3) 그 안에서 지나가는 엔트리의 lstat 소유자를 담는가.
     * 합성 {@code Provenance}만으로는 "verifyProvenance가 그 사실을 실제로 채우는가"를 증명하지 못한다.
     */
    @Test
    void ancestorsAbove_recordsTheOwnerOfEachDirectoryAndOfTheEntryWeTraverse() throws Exception {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path open = Files.createDirectories(tmp.resolve("open/a"));
        Path data = Files.createDirectories(tmp.resolve("secure/final/data"));
        Files.createDirectories(tmp.resolve("safe"));
        Files.createSymbolicLink(tmp.resolve("safe/l1"), open);
        Files.createSymbolicLink(open.resolve("l2"), tmp.resolve("secure/final"));

        List<BreakGlassFile.Ancestor> chain = BreakGlassFile.ancestorsAbove(tmp.resolve("safe/l1/l2/data"));

        assertThat(chain).isNotEmpty().allSatisfy(a -> {
            assertThat(a.owner()).as("%s 의 소유자", a.path())
                .isEqualTo(Files.getOwner(Paths.get(a.path())).getName());
            assertThat(a.entryOwner()).as("%s 안의 엔트리 %s 의 소유자", a.path(), a.entry())
                .isEqualTo(Files.getOwner(Paths.get(a.path()).resolve(a.entry()),
                    LinkOption.NOFOLLOW_LINKS).getName());
        });
        assertThat(chain).extracting(BreakGlassFile.Ancestor::path)
            .contains(real(tmp.resolve("open")),          // 중간 심링크 타깃의 조상 — 여기가 빠지면 위 반례가 뚫린다
                      real(tmp.resolve("open/a")),
                      real(tmp.resolve("safe")),
                      real(tmp.resolve("secure/final")),
                      real(tmp))
            .doesNotContain(real(data));                  // 최종 부모 자신은 조상이 아니다(따로 판정한다)
    }

    /**
     * <b>정상 배포가 여전히 뜨는가.</b> 조상 규칙(소유자 + 쓰기 비트 + 조건부 sticky)이 서로 겹쳐 멀쩡한 배치를
     * 기동 불가로 만들면 그게 이 기능에서 최악의 실패다 — 복구 경로가 복구를 막는다.
     * 이 호스트에서 실제로 만들 수 있는 두 형태를 실파일로 확인한다:
     * <ul>
     *   <li>홈 디렉토리 아래 — 조상이 앱 실행 계정 소유({@code $HOME})와 root({@code /Users}, {@code /})</li>
     *   <li>{@code /tmp} 아래 — root 소유 <b>sticky 1777</b> 조상을 지나간다(조건부 예외가 실제로 통과하는 형태)</li>
     * </ul>
     * {@code /var/lib/worknote}·root 소유 {@code /data} 형태는 조상이 전부 root/앱 계정이라 같은 규칙을 지나며,
     * 그 판정은 {@link #violation_acceptsAncestorsOwnedByTheAppAccountOrRoot()}가 본다(여기서 만들 수 없다).
     */
    @Test
    void verifyProvenance_acceptsTheDeploymentLayoutsThatExistOnThisHost() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        for (Path base : List.of(Paths.get(System.getProperty("user.home")), Paths.get("/tmp"))) {
            assumeTrue(Files.isDirectory(base) && Files.isWritable(base), base + " 를 쓸 수 없는 환경 — skip");
            Path data = Files.createTempDirectory(base, "worknote-bg-");
            try {
                Path file = sentinel600(data, "emp=admin\n");
                assertThatCode(() -> BreakGlassFile.verifyProvenance(file))
                    .as("%s 아래의 정상 배치는 통과해야 한다", base)
                    .doesNotThrowAnyException();
            } finally {
                Files.deleteIfExists(data.resolve(BreakGlassFile.FILE_NAME));
                Files.deleteIfExists(data);
            }
        }
    }

    /**
     * 경로 어딘가에서 stat이 막히면(권한 없음 등) 판정할 수 없다 — 이 클래스의 원칙대로 fail-closed.
     *
     * <p>"조상만 stat이 막힌 상태"는 만들 수 없다: 조상의 탐색(x) 비트를 빼면 그 아래로 내려갈 수 없어
     * 파일·부모 stat이 먼저 막힌다. 즉 조상 stat 실패는 구조상 이 경로에 흡수되며, 어느 층에서 나든
     * {@code IOException}은 같은 catch로 들어가 기동 실패가 된다.
     */
    @Test
    void verifyProvenance_failsClosedWhenThePathCannotBeStatted() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path outer = Files.createDirectories(tmp.resolve("closed"));
        Path data = Files.createDirectories(outer.resolve("worknote"));
        Path file = sentinel600(data, "emp=admin\n");
        Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rw-------"));   // 탐색(x) 제거
        try {
            assumeTrue(!Files.isReadable(file), "접근을 막을 수 없는 환경(root 등) — skip");
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            Files.setPosixFilePermissions(outer, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * 소유자 비교가 <b>실제 파일에서 읽은 값</b>으로 이뤄지는지 — 합성 {@link BreakGlassFile.Provenance}만으로는
     * "verifyProvenance가 그 사실을 실제로 채우는가"를 증명하지 못한다.
     *
     * <p>루트가 아니면 남의 소유 파일을 만들 수 없으므로 반대편을 움직인다: 프로세스 사용자 이름을 바꿔
     * "파일 소유자 ≠ 앱 실행 계정"을 실제로 만든다(비교 대상은 같은 필드다).
     */
    @Test
    void verifyProvenance_rejectsAFileNotOwnedByTheProcessUser() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel600("emp=admin\n");
        String real = System.getProperty("user.name");
        System.setProperty("user.name", real + "-not-me");
        try {
            assertThatThrownBy(() -> BreakGlassFile.verifyProvenance(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("파일 소유자");   // 부모 소유자 검사에 묻히지 않게 파일 쪽을 지목한다
        } finally {
            System.setProperty("user.name", real);
        }
    }

    // ---- read: 무엇을 요구하는가 ----

    @Test
    void read_empOnly_meansTwoFactorResetWithoutPasswordChange() throws IOException {
        BreakGlassFile.Request req = BreakGlassFile.read(write("emp=admin\n"));
        assertThat(req.emp()).isEqualTo("admin");
        assertThat(req.password()).isNull();
    }

    @Test
    void read_empAndPassword() throws IOException {
        BreakGlassFile.Request req = BreakGlassFile.read(write("emp=10001\npassword=place-holder-pw\n"));
        assertThat(req.emp()).isEqualTo("10001");
        assertThat(req.password()).isEqualTo("place-holder-pw");
    }

    /** Properties 포맷을 고른 이유가 이것 — 공백·CRLF·주석을 우리가 다시 다루지 않는다. */
    @Test
    void read_toleratesWhitespaceCommentsAndCrlf() throws IOException {
        BreakGlassFile.Request req = BreakGlassFile.read(write("# 복구\r\n  emp = admin  \r\n"));
        assertThat(req.emp()).isEqualTo("admin");
    }

    @Test
    void read_missingEmpFails() throws IOException {
        assertThatThrownBy(() -> BreakGlassFile.read(write("password=place-holder-pw\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("emp");
    }

    @Test
    void read_blankEmpFails() throws IOException {
        assertThatThrownBy(() -> BreakGlassFile.read(write("emp=   \n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("emp");
    }

    /**
     * 오타 키를 무시하면 최악의 실패가 된다(비밀번호가 바뀐 줄 알고 다시 잠긴 문 앞에 선다). 그래서 세우되,
     * <b>키 이름은 절대 찍지 않는다</b> — Properties는 '=' 없는 줄을 값 없는 키로 만들기 때문에
     * 비밀번호만 적힌 줄이 그대로 키 이름이 되고, 그걸 메시지에 넣으면 스택트레이스에 비밀번호가 박힌다.
     */
    @Test
    void read_unknownKeyFails_withoutEchoingTheKeyName() throws IOException {
        assertThatThrownBy(() -> BreakGlassFile.read(write("emp=admin\npasword=place-holder-pw\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1개")
            .hasMessageContaining("emp")
            .hasMessageNotContaining("pasword")
            .hasMessageNotContaining("place-holder-pw");
    }

    @Test
    void read_barePasswordLineNeverAppearsInTheMessage() throws IOException {
        String secret = "bare-place-holder-pw";
        assertThatThrownBy(() -> BreakGlassFile.read(write(secret + "\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(secret);
    }

    @Test
    void read_shortPasswordFails_andNeverEchoesTheValue() throws IOException {
        assertThatThrownBy(() -> BreakGlassFile.read(write("emp=admin\npassword=short\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("10")
            .hasMessageNotContaining("short");
    }

    /** 상한을 넘긴 비밀번호는 설정돼도 로그인 DTO가 막는다 — 성공한 척하고 다시 잠그느니 세운다. */
    @Test
    void read_overlongPasswordFails() throws IOException {
        String tooLong = "p".repeat(PasswordPolicy.MAX_LENGTH + 1);
        assertThatThrownBy(() -> BreakGlassFile.read(write("emp=admin\npassword=" + tooLong + "\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(PasswordPolicy.MAX_LENGTH))
            .hasMessageNotContaining(tooLong);
    }

    /** 값 없는 password= 를 "미지정"으로 봐주면 운영자는 비번을 바꿨다고 믿는다 — 모호하면 세운다. */
    @Test
    void read_emptyPasswordValueFails() throws IOException {
        assertThatThrownBy(() -> BreakGlassFile.read(write("emp=admin\npassword=\n")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("password");
    }

    @Test
    void read_unreadableContentFails() throws IOException {
        Path f = tmp.resolve("break-glass");
        Files.write(f, new byte[] {(byte) 0xC3, (byte) 0x28});   // 깨진 UTF-8
        assertThatThrownBy(() -> BreakGlassFile.read(f))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("break-glass");
    }

    @Test
    void read_missingFileFails() {
        assertThatThrownBy(() -> BreakGlassFile.read(tmp.resolve("nope")))
            .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers ----

    private boolean posix() {
        return tmp.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /**
     * 조상 판정·에러 메시지는 <b>실경로</b> 기준이다(운영자가 chmod·이동할 대상이 그것이다).
     * macOS의 임시 디렉토리는 {@code /var -> private/var}를 지나므로 렉시컬 경로와 갈린다.
     */
    private static String real(Path path) throws IOException {
        return path.toRealPath().toString();
    }

    private Path write(String content) throws IOException {
        Path f = tmp.resolve("break-glass");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private Path sentinel600(String content) throws IOException {
        return sentinel600(tmp, content);
    }

    /** 운영자가 규정대로 만든 센티넬 — 600, 부모 700. */
    private static Path sentinel600(Path dir, String content) throws IOException {
        Path f = dir.resolve("break-glass");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return f;
    }

    /** {@code chmod 1777} — sticky는 POSIX 뷰에 없어 raw mode로만 세울 수 있다(못 세우면 테스트를 skip한다). */
    private static boolean setSticky1777(Path dir) {
        try {
            Files.setAttribute(dir, "unix:mode", 01777);   // 8진 리터럴 — sticky(1) + rwxrwxrwx
            return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    private static BreakGlassFile.Provenance perms(BreakGlassFile.Provenance p, String mode) {
        return copy(p, p.owner(), PosixFilePermissions.fromString(mode), p.parentOwner(), p.parentPerms(), p.size());
    }

    private static BreakGlassFile.Provenance parentPerms(BreakGlassFile.Provenance p, String mode) {
        return copy(p, p.owner(), p.perms(), p.parentOwner(), PosixFilePermissions.fromString(mode), p.size());
    }

    private static BreakGlassFile.Provenance owner(BreakGlassFile.Provenance p, String who) {
        return copy(p, who, p.perms(), p.parentOwner(), p.parentPerms(), p.size());
    }

    private static BreakGlassFile.Provenance parentOwner(BreakGlassFile.Provenance p, String who) {
        return copy(p, p.owner(), p.perms(), who, p.parentPerms(), p.size());
    }

    private static BreakGlassFile.Provenance ancestors(BreakGlassFile.Provenance p,
            BreakGlassFile.Ancestor... chain) {
        return new BreakGlassFile.Provenance(p.symlink(), p.regularFile(), p.size(), p.owner(), p.perms(),
            p.parentOwner(), p.parentPerms(), List.of(chain));
    }

    private static BreakGlassFile.Provenance size(BreakGlassFile.Provenance p, long bytes) {
        return copy(p, p.owner(), p.perms(), p.parentOwner(), p.parentPerms(), bytes);
    }

    private static BreakGlassFile.Provenance copy(BreakGlassFile.Provenance p, String owner,
            Set<PosixFilePermission> perms, String parentOwner, Set<PosixFilePermission> parentPerms, long size) {
        return new BreakGlassFile.Provenance(
            p.symlink(), p.regularFile(), size, owner, perms, parentOwner, parentPerms, p.ancestors());
    }
}
