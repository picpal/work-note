package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
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

    // --- 디렉토리 700 ---

    @Test
    void ensureDirectory_createsMissingWith700(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = tmp.resolve("data/worknote");
        StoragePermissions.ensureDirectory(dir, true);
        assertThat(Files.isDirectory(dir)).isTrue();
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void ensureDirectory_correctsExistingWhenEnforcing(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("loose"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        StoragePermissions.ensureDirectory(dir, true);
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void ensureDirectory_leavesExistingAloneWhenNotEnforcing(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path dir = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        StoragePermissions.ensureDirectory(dir, false);
        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
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
        Path db = Files.createFile(dir.resolve("worknote.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-rw-r--"));
        StoragePermissions.harden(db, tmp.resolve("data/attachments"), true);
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * local 모드(개인 PC·무인증)에선 이미 존재하는 DB 부모 디렉토리를 건드리지 않는다 —
     * 기본 배치가 `./worknote.db`라 부모가 사용자의 작업 디렉토리다. 거길 700으로 바꾸면 사고다.
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
        // 앱이 만든 산출물(DB 파일·업로드 루트)은 local 모드에서도 보정한다
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void harden_correctsExistingUploadRootInBothModes(@TempDir Path tmp) throws IOException {
        assumePosix(tmp);
        Path uploads = Files.createDirectory(tmp.resolve("attachments"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));
        StoragePermissions.harden(null, uploads, false);
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
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
}
