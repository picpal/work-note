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
 * <p><b>원칙 1 — 우리가 만든 것만 우리가 바꾼다.</b> 관리 대상 디렉토리가 앱 소유라는 보장이 없다.
 * DB 부모는 기본값 {@code ./worknote.db}면 실행 계정의 작업 디렉토리이고 {@code WORKNOTE_DB=/tmp/worknote.db}면
 * {@code /tmp}다. 업로드 루트도 마찬가지로 {@code WORKNOTE_UPLOAD_DIR}는 운영자가 주는 값이라
 * {@code /data}·{@code /srv/shared}를 가리킬 수 있다("앱 전용 하위 디렉토리일 것"은 코드의 성질이 아니라 가정이다).
 * 여기에 자동으로 700을 씌우면 백업·그룹 접근을 말없이 끊거나 공용 호스트를 망가뜨린다. 그래서 두 디렉토리에
 * <b>같은 규칙 하나</b>({@link #ensureManagedDirectory})를 쓴다 — <b>없으면 700으로 생성, 있으면 검증만</b>.
 * 열려 있으면 조용히 고치는 대신 경로와 실행할 {@code chmod} 명령을 알려주고 server 모드는 기동을 세운다.
 * local 모드(개인 PC·무인증)에선 이 지적을 아예 하지 않는다 — 근거는 {@link #hardenDirectory} 주석.
 *
 * <p><b>원칙 2 — chmod하는 곳에서는 심볼릭 링크를 거부하고, 검증만 하는 곳에서는 실경로를 해석한다.</b>
 * {@code setPosixFilePermissions}는 링크를 따라가므로 우리가 지정하지도 않은 타깃의 권한이 바뀔 수 있다.
 * 실제로 chmod가 남아 있는 곳은 DB 파일 600뿐이라 거기서만 링크를 거부한다. 디렉토리는 이제 만들 때 말고는
 * 바꾸지 않으므로, 링크(별도 볼륨 마운트 같은 정상 배치)든 조상 링크(macOS {@code /var} → {@code /private/var})든
 * 실경로로 해석해 검증한다. 렉시컬 형제 경로인 SQLite 사이드카도 같은 실디렉토리로 귀결되므로 범위 안에 들어온다.
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

    /** 처음부터 700으로 만든다 — 넓게 만들고 나중에 조이면 그 사이가 노출 구간이다. */
    private static void createAt700(Path dir) throws IOException {
        if (posixSupported(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_700));
        } else {
            Files.createDirectories(dir);
        }
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
     * 관리 대상 디렉토리(DB 부모·업로드 루트)에 적용하는 <b>단 하나의 규칙</b>.
     * 없으면 700으로 생성하고, 이미 있으면 <b>검증만</b> 한다 — 앱이 만들지 않은 디렉토리는 앱이 조이지 않는다.
     * 두 디렉토리가 이 메서드 하나만 거치게 해서 규칙이 갈라질 여지를 없앤다.
     *
     * <p>업로드 루트도 예외가 아니다: {@code WORKNOTE_UPLOAD_DIR}는 운영자가 주는 값이라
     * {@code /data}·{@code /srv/shared}처럼 앱 전용이 아닐 수 있다. "앱 전용일 것"은 코드의 성질이 아니라
     * 운영자 행동에 대한 가정이므로, DB 부모와 규칙을 나누지 않는다.
     *
     * <p>디렉토리는 chmod하지 않으므로 심링크여도 거부할 이유가 없다 — 실경로를 해석해 검증한다
     * (별도 볼륨을 심링크로 붙이는 정상 배치를 깨지 않는다). 거부는 실제로 chmod하는 곳,
     * 즉 {@link #ensureFile(Path)}에만 남는다.
     *
     * <p>모드는 여기서 보지 않는다. "무엇이 문제인가"는 두 모드가 같고, 그 문제를 <b>보고할지·기동을 세울지</b>는
     * {@link #hardenDirectory}와 호출부가 정한다. 그래서 열려 있다는 지적만 {@link Permissive}로 구분해 던진다.
     *
     * @param envHint 조치 안내에 쓸 환경변수 이름(WORKNOTE_DB / WORKNOTE_UPLOAD_DIR)
     */
    public static void ensureManagedDirectory(Path dir, String envHint) throws IOException {
        if (!existsNoFollow(dir)) {
            createAt700(dir);
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new Actionable(dir + " 자리에 디렉토리가 아닌 항목이 있습니다. "
                + envHint + "에 앱 전용 디렉토리 경로를 지정하세요.");
        }
        String shared = describeIfShared(dir);
        if (shared != null) {
            throw new Permissive(shared);
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
     * DB 파일 하나만 600으로. 반환값은 {@link #harden}과 같은 성격의 "운영자가 읽고 조치할 수 있는" 문제 목록.
     *
     * <p>따로 뽑아둔 이유: 이 동작은 <b>두 번</b> 실행돼야 한다. 기동 전 패스는 <i>이미 있는</i> DB만 조일 수 있고
     * (신규 설치에선 파일이 아직 없다), 파일을 실제로 만드는 건 그 뒤의 DataSource/Flyway다.
     * 나머지 규칙(디렉토리 생성·검증)은 두 번 돌 이유가 없으므로 재실행 대상은 이 한 가지뿐이다.
     * 자세한 근거는 {@code StoragePermissionGuard}의 2패스 주석 참조.
     */
    public static List<String> hardenFile(Path file) {
        List<String> problems = new ArrayList<>();
        if (file != null) {
            Path abs = file.toAbsolutePath();
            attempt(problems, abs, () -> ensureFile(abs));
        }
        return problems;
    }

    /**
     * DB 부모 디렉토리·업로드 루트를 700으로, 기존 DB 파일을 600으로 만든다.
     * 반환값은 <b>운영자가 읽고 조치할 수 있는</b> 문제 설명 — 호출부가 모드에 따라 WARN/기동실패를 결정한다.
     *
     * <p>디렉토리에 적용하는 규칙 자체는 두 모드가 같다. 모드가 정하는 건 둘뿐 — <b>DB 경로에 절대 경로를
     * 요구할지</b>, 그리고 <b>기존 디렉토리가 넓다는 지적을 보고할지</b>({@link #hardenDirectory}).
     * 보고된 문제를 기동 실패로 볼지 WARN으로 볼지는 호출부가 정한다.
     *
     * @param serverMode server(공용 서버) 모드 여부. 공용 서버는 DB가 전용 절대 경로에 있을 것을 요구한다.
     *                   local 모드(개인 PC·무인증)는 무설정 기본값 {@code ./worknote.db}로 계속 떠야 한다.
     */
    public static List<String> harden(Path dbFile, Path uploadRoot, boolean serverMode) {
        List<String> problems = new ArrayList<>();
        if (dbFile != null) {
            hardenDb(problems, dbFile, serverMode);
        }
        if (uploadRoot != null) {
            hardenDirectory(problems, uploadRoot, "WORKNOTE_UPLOAD_DIR", serverMode);
        }
        return problems;
    }

    /**
     * 디렉토리 규칙은 두 모드가 같고, <b>보고 정책만</b> 여기서 한 번 갈린다.
     *
     * <p>local 모드에서 {@link Permissive}를 삼키는 이유: 개인 PC는 무인증 단일 사용자고 DB 파일은 이미 600이라
     * 부모가 755여도 다른 계정이 얻는 건 "목록 보기"뿐이다. 반면 보통의 프로젝트·홈 폴더가 755라 이 경고는
     * 거의 모든 개인 설치에서 매 기동 뜬다 — 사용자가 손쓸 수도 없는 경고("내 프로젝트 폴더를 chmod 700 하라")를
     * 계속 띄우면 정작 중요한 경고까지 무시하게 된다. 진짜 오작동(생성 실패·600 설정 실패)은 그대로 보고한다.
     */
    private static void hardenDirectory(List<String> problems, Path dir, String envHint, boolean serverMode) {
        attempt(problems, dir, () -> {
            try {
                ensureManagedDirectory(dir, envHint);
            } catch (Permissive e) {
                if (serverMode) {
                    throw e;
                }
            }
        });
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
            hardenDirectory(problems, parent, "WORKNOTE_DB", serverMode);
        }
        problems.addAll(hardenFile(abs));   // 후반 패스와 문자 그대로 같은 동작이어야 한다
    }

    /** 우리가 만든, 경로와 조치가 이미 들어 있는 설명. JDK 예외 메시지(경로만 있는 경우가 많다)와 구분한다. */
    static class Actionable extends IOException {
        Actionable(String message) {
            super(message);
        }
    }

    /**
     * "앱이 만들지 않은 기존 디렉토리가 그룹/타인에게 열려 있다" — 앱 오작동이 아니라 <b>배치 상태</b>다.
     * 별도 타입인 이유: 이것만 모드별로 보고 여부가 갈리기 때문. 규칙(무엇이 문제인가)은 하나로 두고,
     * 보고 정책만 {@link #hardenDirectory}에서 한 번 판단한다.
     */
    static final class Permissive extends Actionable {
        Permissive(String message) {
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
