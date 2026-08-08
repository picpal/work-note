package com.worknote.config;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * M-6 — 데이터 저장소 권한 하드닝의 순수 로직.
 *
 * <p>파일 단위 chmod로는 부족하다: SQLite는 실행 중 {@code -wal}/{@code -shm}/journal을 계속 새로 만들고,
 * DB 파일 자체는 Flyway/DataSource가 어떤 러너보다도 먼저 만든다. 그래서 <b>디렉토리가 실제 통제 지점</b>이다 —
 * 부모 디렉토리가 700이면 그 안에 무엇이 생기든 다른 계정이 열 수 없다.
 *
 * <p>POSIX 미지원 파일시스템(Windows 등)에서는 조용히 skip한다. 권한 강화 실패로 기동을 막지 않는다.
 */
public final class StoragePermissions {

    private static final Set<PosixFilePermission> DIR_700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_600 = PosixFilePermissions.fromString("rw-------");

    private StoragePermissions() {}

    public static boolean posixSupported(Path path) {
        FileSystem fs = path == null ? null : path.getFileSystem();
        return fs != null && fs.supportedFileAttributeViews().contains("posix");
    }

    /**
     * JDBC URL에서 실제 DB 파일 경로를 뽑는다. 인메모리·비-SQLite면 null — 보정할 파일이 없다.
     * (테스트는 전부 인메모리라 여기서 걸러진다)
     */
    public static Path sqliteFile(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String url = jdbcUrl.trim();
        if (!url.startsWith("jdbc:sqlite:")) {
            return null;
        }
        String rest = url.substring("jdbc:sqlite:".length());
        int q = rest.indexOf('?');
        String query = q >= 0 ? rest.substring(q + 1) : "";
        String path = q >= 0 ? rest.substring(0, q) : rest;
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.isBlank() || path.contains(":memory:") || query.contains("mode=memory")) {
            return null;
        }
        try {
            return Paths.get(path);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 디렉토리를 700으로. 없으면 그 권한으로 생성한다(넓게 만들고 나중에 chmod 하지 않는다).
     *
     * @param correctExisting 이미 존재하는 디렉토리의 권한까지 바꿀지. false면 생성만 하고 기존 권한은 존중한다.
     */
    public static void ensureDirectory(Path dir, boolean correctExisting) throws IOException {
        if (Files.isDirectory(dir)) {
            if (correctExisting && posixSupported(dir)) {
                Files.setPosixFilePermissions(dir, DIR_700);
            }
            return;
        }
        if (posixSupported(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_700));
        } else {
            Files.createDirectories(dir);
        }
    }

    /** 이미 존재하는 파일을 600으로. 없으면 아무것도 하지 않는다. */
    public static void ensureFile(Path file) throws IOException {
        if (Files.isRegularFile(file) && posixSupported(file)) {
            Files.setPosixFilePermissions(file, FILE_600);
        }
    }

    /**
     * DB 부모 디렉토리·업로드 루트를 700으로, 기존 DB 파일을 600으로 만든다.
     * 반환값은 보정에 실패한 항목 설명 — 호출부가 모드에 따라 WARN/기동실패를 결정한다.
     *
     * @param serverMode 이미 존재하는 <b>DB 부모</b> 디렉토리까지 보정할지. local 모드 기본 배치는
     *                   DB 부모가 사용자의 작업 디렉토리(`./worknote.db`)라 임의로 700을 씌우면 사고가 된다.
     *                   업로드 루트와 DB 파일은 앱 소유 산출물이라 두 모드 모두 보정한다.
     */
    public static List<String> harden(Path dbFile, Path uploadRoot, boolean serverMode) {
        List<String> problems = new ArrayList<>();
        if (dbFile != null) {
            Path parent = dbFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                attempt(problems, parent, () -> ensureDirectory(parent, serverMode));
            }
            attempt(problems, dbFile, () -> ensureFile(dbFile));
        }
        if (uploadRoot != null) {
            attempt(problems, uploadRoot, () -> ensureDirectory(uploadRoot, true));
        }
        return problems;
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void attempt(List<String> problems, Path target, Action action) {
        try {
            action.run();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            problems.add(target + " 권한 보정 실패: " + e);
        }
    }
}
