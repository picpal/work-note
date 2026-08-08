package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M-6 — DB·업로드 저장소 권한 하드닝의 순수 로직. POSIX 미지원 파일시스템에선 권한 단언을 skip. */
class StoragePermissionsTest {

    private static void assumePosix(Path base) {
        assumeTrue(StoragePermissions.posixSupported(base), "POSIX 미지원 파일시스템 — skip");
    }

    // --- JDBC URL 파싱 (인메모리 DB에는 보정할 파일이 없다) ---

    @Test
    void sqliteFile_parsesPlainPath() {
        assertThat(StoragePermissions.sqliteFile("jdbc:sqlite:/data/worknote/worknote.db"))
            .isEqualTo(Paths.get("/data/worknote/worknote.db"));
    }

    @Test
    void sqliteFile_parsesFilePrefixAndStripsQuery() {
        assertThat(StoragePermissions.sqliteFile("jdbc:sqlite:file:/data/w.db?busy_timeout=5000"))
            .isEqualTo(Paths.get("/data/w.db"));
    }

    @Test
    void sqliteFile_returnsNullForInMemory() {
        assertThat(StoragePermissions.sqliteFile("jdbc:sqlite::memory:")).isNull();
        assertThat(StoragePermissions.sqliteFile("jdbc:sqlite:file::memory:?cache=shared")).isNull();
        assertThat(StoragePermissions.sqliteFile("jdbc:sqlite:file:t1?mode=memory&cache=shared")).isNull();
    }

    @Test
    void sqliteFile_returnsNullForNonSqliteOrBlank() {
        assertThat(StoragePermissions.sqliteFile(null)).isNull();
        assertThat(StoragePermissions.sqliteFile("")).isNull();
        assertThat(StoragePermissions.sqliteFile("jdbc:h2:mem:test")).isNull();
    }

    // --- 기존 디렉토리 검증(변경 없음) ---

    @Test
    void describeIfShared_returnsNullForOwnerOnlyDirectory(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("private"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        assertThat(StoragePermissions.describeIfShared(dir)).isNull();
    }

    @Test
    void describeIfShared_flagsGroupOrWorldAccessWithChmodCommand(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("loose"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-x---"));   // 그룹만 열려도 지적
        assertThat(StoragePermissions.describeIfShared(dir))
            .contains(dir.toString())
            .contains("chmod 700 " + dir);
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));   // 검증만 — 바꾸지 않는다
    }

    // --- 관리 대상 디렉토리 단일 규칙 (DB 부모·업로드 루트 공통) ---

    @Test
    void ensureManagedDirectory_createsMissingAt700(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = tmp.resolve("data/attachments");   // 중간 디렉토리까지 만든다
        StoragePermissions.ensureManagedDirectory(dir, "WORKNOTE_UPLOAD_DIR");
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void ensureManagedDirectory_rejectsExistingPermissiveWithoutChanging(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("attachments"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> StoragePermissions.ensureManagedDirectory(dir, "WORKNOTE_UPLOAD_DIR"))
            .hasMessageContaining("chmod 700 " + dir);
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 디렉토리는 chmod하지 않으므로 심링크여도 거부하지 않는다 — 실경로를 해석해 검증한다. */
    @Test
    void ensureManagedDirectory_acceptsSymlinkWhenRealTargetIsPrivate(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path target = Files.createDirectory(tmp.resolve("volume"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        Path link = Files.createSymbolicLink(tmp.resolve("attachments"), target);
        StoragePermissions.ensureManagedDirectory(link, "WORKNOTE_UPLOAD_DIR");   // 예외 없음
        assertThat(Files.getPosixFilePermissions(target))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    // --- 파일 600 ---

    @Test
    void ensureFile_correctsExistingTo600(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path db = Files.createFile(tmp.resolve("worknote.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-r--r--"));
        StoragePermissions.ensureFile(db);
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void ensureFile_ignoresMissingFile(@TempDir Path tmp) throws IOException {
        StoragePermissions.ensureFile(tmp.resolve("does-not-exist.db"));   // 예외 없음
    }

    // --- harden() 통합 ---

    @Test
    void harden_hardensDbParentAndUploadRoot(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path db = tmp.resolve("data/worknote.db");
        Path uploads = tmp.resolve("data/attachments");
        List<String> problems = StoragePermissions.harden(db, uploads, true);
        assertThat(problems).isEmpty();
        assertThat(Files.getPosixFilePermissions(db.getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void harden_correctsExistingDbFile(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path db = Files.createFile(dir.resolve("worknote.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-rw-r--"));
        StoragePermissions.harden(db, tmp.resolve("data/attachments"), true);
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * local 모드(개인 PC·무인증)에서도 이미 존재하는 DB 부모 디렉토리를 건드리지 않는다 —
     * 기본 배치가 `./worknote.db`라 부모가 사용자의 작업 디렉토리다. 거길 700으로 바꾸면 사고다.
     * (보고만 하고 기동을 세우지 않는 건 호출부 판단 — StoragePermissionGuardTest)
     */
    @Test
    void harden_leavesExistingDbParentAloneInLocalMode(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("cwd"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path db = Files.createFile(dir.resolve("worknote.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(StoragePermissions.harden(db, tmp.resolve("att"), false)).isEmpty();

        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        // DB 파일은 앱이 만든 산출물이자 실제 데이터라 두 모드 모두 600으로 조인다
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void harden_reportsProblemWhenDirectoryCannotBeCreated(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path blocker = Files.createFile(tmp.resolve("attachments"));   // 파일이 자리를 막고 있음
        List<String> problems = StoragePermissions.harden(null, blocker, true);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(blocker.toString());
    }

    @Test
    void harden_toleratesNullDbFile(@TempDir Path tmp) {
        assertThat(StoragePermissions.harden(null, tmp.resolve("attachments"), true)).isEmpty();
    }

    @Test
    void harden_skipsSilentlyOnNonPosix(@TempDir Path tmp) {
        assumeTrue(!StoragePermissions.posixSupported(tmp), "POSIX 파일시스템 — 이 케이스 대상 아님");
        assertThat(StoragePermissions.harden(tmp.resolve("w.db"), tmp.resolve("att"), true)).isEmpty();
    }

    // --- P1: 이미 존재하는 디렉토리는 절대 chmod하지 않는다 ---

    /**
     * 핵심 회귀 — 기존 DB 부모 디렉토리는 앱 소유가 아닐 수 있다(기본 배치는 작업 디렉토리,
     * WORKNOTE_DB=/tmp/worknote.db면 /tmp). server 모드라도 임의로 700을 씌우지 않고 기동을 세운다.
     */
    @Test
    void harden_serverMode_doesNotChmodExistingDbParent_andFailsWithActionableMessage(@TempDir Path tmp)
        throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path db = dir.resolve("worknote.db");

        List<String> problems = StoragePermissions.harden(db, tmp.resolve("att"), true);

        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(dir.toString()).contains("chmod 700 " + dir);
    }

    /** 700보다 엄격/동일하면 문제 없음 — 정상 배치는 조용히 통과해야 한다. */
    @Test
    void harden_serverMode_acceptsExistingPrivateDbParent(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("private"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        assertThat(StoragePermissions.harden(dir.resolve("worknote.db"), tmp.resolve("att"), true)).isEmpty();
    }

    /** 없는 디렉토리는 우리가 만든 것이므로 700으로 생성한다(넓게 만들고 나중에 조이지 않는다). */
    @Test
    void harden_serverMode_createsMissingDbParentAt700(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path db = tmp.resolve("var/lib/worknote/worknote.db");
        assertThat(StoragePermissions.harden(db, tmp.resolve("att"), true)).isEmpty();
        assertThat(Files.getPosixFilePermissions(db.getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /** server 모드에서 상대 경로 DB는 거부 — 작업 디렉토리에 따라 위치가 달라지는 경로는 전용 저장소가 아니다. */
    @Test
    void harden_serverMode_rejectsRelativeDbPath(@TempDir Path tmp) {
        assumePosix(tmp);
        List<String> problems = StoragePermissions.harden(Paths.get("./worknote.db"), tmp.resolve("att"), true);
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("WORKNOTE_DB");
    }

    /** local 모드(개인 PC 기본값)는 무설정으로 계속 떠야 한다 — 상대 경로 DB 자체는 문제 삼지 않는다. */
    @Test
    void harden_localMode_acceptsRelativeDefaultDbPath(@TempDir Path tmp) {
        assertThat(StoragePermissions.harden(Paths.get("./worknote.db"), tmp.resolve("att"), false)).isEmpty();
    }

    // --- P2: 심볼릭 링크를 따라가지 않는다 ---

    /** DB 파일이 심링크면 chmod가 링크 타깃(우리 소유가 아닐 수 있는 파일)에 적용된다 — 거부. */
    @Test
    void harden_doesNotChmodSymlinkTarget(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path victim = Files.createFile(tmp.resolve("victim.txt"));
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-r--r--"));
        Path link = Files.createSymbolicLink(dir.resolve("worknote.db"), victim);

        List<String> problems = StoragePermissions.harden(link, tmp.resolve("att"), true);

        assertThat(Files.getPosixFilePermissions(victim))
            .isEqualTo(PosixFilePermissions.fromString("rw-r--r--"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("심볼릭 링크");
    }

    /**
     * 업로드 루트도 DB 부모와 같은 규칙 — WORKNOTE_UPLOAD_DIR는 운영자가 주는 값이라
     * `/data`·`/srv/shared`처럼 앱 전용이 아닐 수 있다. 이미 있으면 검증만 한다.
     */
    @Test
    void harden_serverMode_doesNotChmodExistingUploadRoot_andFailsWithActionableMessage(@TempDir Path tmp)
        throws IOException {
        assumePosix(tmp);
        Path uploads = Files.createDirectory(tmp.resolve("shared-uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));

        List<String> problems = StoragePermissions.harden(null, uploads, true);

        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(uploads.toString()).contains("chmod 700 " + uploads);
    }

    /**
     * local 모드는 chmod도 하지 않고 <b>보고도 하지 않는다</b>. 개인 PC에서 작업 디렉토리·기존 폴더가 755인 건
     * 정상이고(DB 파일은 600이라 내용은 안전), 매 기동 "chmod 700 내 프로젝트 폴더" 경고는 로그를 무디게 만든다.
     */
    @Test
    void harden_localMode_doesNotReportOrChmodExistingPermissiveDirectories(@TempDir Path tmp)
        throws IOException {
        assumePosix(tmp);
        Path cwd = Files.createDirectory(tmp.resolve("cwd"));
        Files.setPosixFilePermissions(cwd, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path uploads = Files.createDirectory(tmp.resolve("shared-uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(StoragePermissions.harden(cwd.resolve("worknote.db"), uploads, false)).isEmpty();

        assertThat(Files.getPosixFilePermissions(cwd))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 반면 진짜 오작동(업로드 루트를 만들 수 없음)은 local 모드에서도 보고한다 — 침묵시키는 건 권한 넓음뿐. */
    @Test
    void harden_localMode_stillReportsRealMalfunctions(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path blocker = Files.createFile(tmp.resolve("attachments"));
        assertThat(StoragePermissions.harden(null, blocker, false)).hasSize(1);
    }

    /** 업로드 루트가 심링크면 타깃을 chmod하지 않고, 타깃의 실제 권한을 검증해 실경로까지 알려준다. */
    @Test
    void harden_doesNotChmodSymlinkedUploadRoot(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path target = Files.createDirectory(tmp.resolve("elsewhere"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path link = Files.createSymbolicLink(tmp.resolve("attachments"), target);

        List<String> problems = StoragePermissions.harden(null, link, true);

        assertThat(Files.getPosixFilePermissions(target))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(target.toRealPath().toString());
    }

    /**
     * SQLite 사이드카(-wal/-shm/-journal)는 DB 경로 문자열 옆에 렉시컬하게 생긴다.
     * 하드닝한 디렉토리가 그 렉시컬 부모의 실경로와 같아야 사이드카가 실제로 덮인다.
     */
    @Test
    void harden_hardenedDirectoryActuallyContainsSqliteSidecars(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path db = tmp.resolve("data/worknote.db");
        assertThat(StoragePermissions.harden(db, tmp.resolve("att"), true)).isEmpty();

        Path wal = Files.createFile(db.resolveSibling(db.getFileName() + "-wal"));
        assertThat(wal.getParent().toRealPath()).isEqualTo(db.getParent().toRealPath());
        assertThat(Files.getPosixFilePermissions(wal.getParent().toRealPath()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }
}
