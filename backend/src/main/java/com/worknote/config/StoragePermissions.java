package com.worknote.config;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * M-6 — 데이터 저장소 권한 하드닝의 순수 로직.
 *
 * <p>파일 단위 chmod로는 부족하다: SQLite는 실행 중 {@code -wal}/{@code -shm}/journal을 계속 새로 만들고,
 * DB 파일 자체는 Flyway/DataSource가 어떤 러너보다도 먼저 만든다. 그래서 <b>디렉토리가 실제 통제 지점</b>이다 —
 * 부모 디렉토리가 700이면 그 안에 무엇이 생기든 다른 계정이 열 수 없다.
 *
 * <p><b>원칙 1 — 우리가 만든 것만 우리가 바꾼다.</b> DB 부모 디렉토리는 앱 소유라는 보장이 없다.
 * 기본값 {@code ./worknote.db}면 부모는 실행 계정의 작업 디렉토리이고, {@code WORKNOTE_DB=/tmp/worknote.db}면
 * {@code /tmp}다. 여기에 자동으로 700을 씌우면 백업·그룹 접근을 말없이 끊거나 공용 호스트를 망가뜨린다.
 * 그래서 <b>없으면 700으로 생성, 있으면 검증만</b> 한다. server 모드에서 기존 디렉토리가 열려 있으면
 * 조용히 고치는 대신 경로와 실행할 {@code chmod} 명령을 알려주고 기동을 세운다.
 *
 * <p><b>원칙 2 — 심볼릭 링크를 따라가서 chmod하지 않는다.</b> {@code setPosixFilePermissions}는 링크를 따라가므로
 * 우리가 지정하지도 않은 타깃의 권한이 바뀔 수 있다. 관리 대상 경로의 마지막 구성요소가 링크면 거부한다.
 * 조상 경로의 링크(macOS {@code /var} → {@code /private/var} 등)는 정상적인 배치라 해석해서 실경로로 다룬다 —
 * 렉시컬 형제 경로인 SQLite 사이드카도 같은 실디렉토리로 귀결되므로 하드닝 범위 안에 들어온다.
 *
 * <p>POSIX 미지원 파일시스템(Windows 등)에서는 조용히 skip한다. 권한 강화 실패로 기동을 막지 않는다.
 */
public final class StoragePermissions {

    private static final Set<PosixFilePermission> DIR_700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_600 = PosixFilePermissions.fromString("rw-------");

    /** 그룹·타인에게 열려 있음을 판정하는 비트 — 하나라도 켜져 있으면 소유자 전용이 아니다. */
    private static final Set<PosixFilePermission> SHARED_BITS = EnumSet.of(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

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

    /** 경로가 존재하는지 — 링크를 따라가지 않는다(깨진 링크도 "존재"로 본다). */
    private static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 관리 대상 디렉토리를 <b>없을 때만</b> 700으로 생성한다. 이미 있으면 손대지 않는다 —
     * 기존 디렉토리의 권한 판단은 {@link #describeIfShared(Path)}가 하고, 고치는 건 운영자 몫이다.
     *
     * @return 실제 디렉토리 경로(조상 심링크 해석 후). 생성도 못 하고 존재하지도 않으면 IOException.
     */
    public static Path createDirectoryIfMissing(Path dir) throws IOException {
        if (!existsNoFollow(dir)) {
            if (posixSupported(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_700));
            } else {
                Files.createDirectories(dir);
            }
        }
        if (!Files.isDirectory(dir)) {
            throw new Actionable(dir + " 자리에 디렉토리가 아닌 항목이 있습니다. 앱 전용 디렉토리 경로로 지정하세요.");
        }
        return dir.toRealPath();
    }

    /**
     * 이미 존재하는 디렉토리가 그룹/타인에게 열려 있으면 <b>운영자가 실행할 명령까지 담은</b> 설명을,
     * 소유자 전용이면 null을 돌려준다. 이 메서드는 아무것도 바꾸지 않는다.
     *
     * <p>권한은 실경로에서 읽되 메시지는 설정된 경로로 안내한다 — 운영자가 알아보는 건 자기가 적은 경로다.
     * (조상이 심링크여도 {@code chmod}는 같은 실디렉토리에 적용된다. 다르면 실경로도 함께 표기)
     */
    public static String describeIfShared(Path dir) throws IOException {
        if (!posixSupported(dir)) {
            return null;
        }
        Path real = dir.toRealPath();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(real);
        if (perms.stream().noneMatch(SHARED_BITS::contains)) {
            return null;
        }
        String realNote = real.equals(dir) ? "" : " (실경로 " + real + ")";
        return dir + " 디렉토리가 그룹/타인에게 열려 있습니다(현재 "
            + PosixFilePermissions.toString(perms) + ")" + realNote + ". 앱이 임의로 바꾸지 않습니다 — "
            + "운영자가 직접 실행하세요: chmod 700 " + dir;
    }

    /**
     * 앱이 전적으로 소유하는 디렉토리(업로드 루트)를 700으로. 없으면 생성, 있으면 보정한다.
     * 단, 마지막 구성요소가 심링크면 타깃을 바꾸게 되므로 거부한다.
     */
    public static void ensureAppOwnedDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new Actionable(dir + " 은(는) 심볼릭 링크입니다. 링크 타깃의 권한은 앱이 바꾸지 않습니다 — "
                + "WORKNOTE_UPLOAD_DIR에 실제 디렉토리 경로를 지정하세요.");
        }
        Path real = createDirectoryIfMissing(dir);
        if (posixSupported(real)) {
            Files.setPosixFilePermissions(real, DIR_700);
        }
    }

    /** 이미 존재하는 파일을 600으로. 없으면 아무것도 하지 않고, 심링크면 거부한다. */
    public static void ensureFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new Actionable(file + " 은(는) 심볼릭 링크입니다. 링크 타깃의 권한은 앱이 바꾸지 않습니다 — "
                + "WORKNOTE_DB에 실제 파일 경로를 지정하세요.");
        }
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && posixSupported(file)) {
            Files.setPosixFilePermissions(file, FILE_600);
        }
    }

    /**
     * DB 부모 디렉토리·업로드 루트를 700으로, 기존 DB 파일을 600으로 만든다.
     * 반환값은 <b>운영자가 읽고 조치할 수 있는</b> 문제 설명 — 호출부가 모드에 따라 WARN/기동실패를 결정한다.
     *
     * @param serverMode server(공용 서버) 모드 여부. 공용 서버는 DB가 전용 절대 경로에 있고 그 디렉토리가
     *                   소유자 전용이어야 한다는 걸 <b>강제</b>한다. local 모드(개인 PC·무인증)는 무설정
     *                   기본값 {@code ./worknote.db}로 계속 떠야 하므로 검증하지 않는다.
     */
    public static List<String> harden(Path dbFile, Path uploadRoot, boolean serverMode) {
        List<String> problems = new ArrayList<>();
        if (dbFile != null) {
            hardenDb(problems, dbFile, serverMode);
        }
        if (uploadRoot != null) {
            attempt(problems, uploadRoot, () -> ensureAppOwnedDirectory(uploadRoot));
        }
        return problems;
    }

    private static void hardenDb(List<String> problems, Path dbFile, boolean serverMode) {
        // 상대 경로 = 실행 계정의 작업 디렉토리에 따라 위치가 바뀌는 경로. 공용 서버에서 이건 전용 저장소가 아니고,
        // 그 부모를 우리가 하드닝해도 되는지 판단할 근거도 없다. 그래서 server 모드에선 절대 경로를 요구한다.
        if (serverMode && !dbFile.isAbsolute()) {
            problems.add("server 모드에서는 DB 경로가 절대 경로여야 합니다(현재 '" + dbFile + "'). "
                + "앱 전용 디렉토리를 지정하세요 — 예: WORKNOTE_DB=/var/lib/worknote/worknote.db");
            return;
        }
        // normalize()는 렉시컬이라 `..`가 심링크 조상을 지나가면 OS 해석과 갈린다 — 정규화는 toRealPath()에 맡긴다.
        Path abs = dbFile.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent != null) {
            if (existsNoFollow(parent)) {
                // 이미 있는 디렉토리는 절대 chmod하지 않는다. server 모드에서만 열려 있는지 검증한다.
                if (serverMode) {
                    attempt(problems, parent, () -> {
                        String shared = describeIfShared(parent);
                        if (shared != null) {
                            throw new Actionable(shared);
                        }
                    });
                }
            } else {
                // 없는 디렉토리는 우리가 만든 것 — 처음부터 700으로 만든다(넓게 만들고 나중에 조이지 않는다).
                attempt(problems, parent, () -> createDirectoryIfMissing(parent));
            }
        }
        attempt(problems, abs, () -> ensureFile(abs));
    }

    /** 우리가 만든, 경로와 조치가 이미 들어 있는 설명. JDK 예외 메시지(경로만 있는 경우가 많다)와 구분한다. */
    static final class Actionable extends IOException {
        Actionable(String message) {
            super(message);
        }
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void attempt(List<String> problems, Path target, Action action) {
        try {
            action.run();
        } catch (Actionable e) {
            problems.add(e.getMessage());   // 중복 접두어 없이 그대로 — 이미 읽을 수 있는 문장이다
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            problems.add(target + " 권한 보정 실패: " + e);
        }
    }
}
