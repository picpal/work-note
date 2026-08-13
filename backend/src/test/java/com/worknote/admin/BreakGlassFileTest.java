package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.worknote.auth.PasswordPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
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

    // ---- violation: 출처 검증(이 기능의 보안 논거 자체) ----
    //
    // 전제는 "700 앱 소유 디렉토리에 파일을 만들 수 있는 능력 = 이미 DB를 직접 고칠 수 있는 능력"이다.
    // 그 전제가 실제로 성립하는지 매번 확인한다 — 주장만 하고 검사하지 않으면 그냥 뒷문이다.

    private static BreakGlassFile.Provenance ok() {
        return new BreakGlassFile.Provenance(false, true, 40, ME,
            PosixFilePermissions.fromString("rw-------"), ME, PosixFilePermissions.fromString("rwx------"));
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
     * 핵심 케이스 — WORKNOTE_STORAGE_STRICT=false로 755 부모를 허용한 배치에서, DB(600)를 못 읽는
     * 다른 로컬 사용자가 센티넬만 만들어 관리자 계정을 가져갈 수 있었다. 그건 "새 권한 없음"이 아니다.
     */
    @Test
    void violation_rejectsPermissiveParentDirectory() {
        assertThat(BreakGlassFile.violation(parentPerms(ok(), "rwxr-xr-x"), ME)).contains("700");
        assertThat(BreakGlassFile.violation(parentPerms(ok(), "rwxrwxrwx"), ME)).contains("700");
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
            PosixFilePermissions.fromString("rwx------")), ME)).contains("심볼릭");
        assertThat(BreakGlassFile.violation(new BreakGlassFile.Provenance(false, false, 40, ME,
            PosixFilePermissions.fromString("rw-------"), ME,
            PosixFilePermissions.fromString("rwx------")), ME)).contains("일반 파일");
    }

    @Test
    void violation_rejectsOversizedFile() {
        assertThat(BreakGlassFile.violation(size(ok(), BreakGlassFile.MAX_BYTES + 1), ME)).contains("큽니다");
        assertThat(BreakGlassFile.violation(size(ok(), BreakGlassFile.MAX_BYTES), ME)).isNull();
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

    private Path write(String content) throws IOException {
        Path f = tmp.resolve("break-glass");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    private Path sentinel600(String content) throws IOException {
        Path f = write(content);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
        return f;
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

    private static BreakGlassFile.Provenance size(BreakGlassFile.Provenance p, long bytes) {
        return copy(p, p.owner(), p.perms(), p.parentOwner(), p.parentPerms(), bytes);
    }

    private static BreakGlassFile.Provenance copy(BreakGlassFile.Provenance p, String owner,
            Set<PosixFilePermission> perms, String parentOwner, Set<PosixFilePermission> parentPerms, long size) {
        return new BreakGlassFile.Provenance(p.symlink(), p.regularFile(), size, owner, perms, parentOwner, parentPerms);
    }
}
