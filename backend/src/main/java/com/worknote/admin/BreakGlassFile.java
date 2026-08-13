package com.worknote.admin;

import com.worknote.auth.PasswordPolicy;
import com.worknote.config.StoragePermissions;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 브레이크글래스 센티넬 파일의 <b>위치 판정·출처 검증·파싱</b> — 스프링·DB를 모르는 순수 로직.
 * 실제 계정 수술과 기동 연결은 {@link BreakGlassRecovery}가 한다.
 *
 * <p><b>왜 파일인가.</b> 이 경로는 잠긴 계정을 되살리므로 뒷문이다. 성립하는 근거는 단 하나 —
 * 트리거가 <b>DB 부모 디렉토리(700, 앱 계정 소유)에 파일을 만들 수 있는 능력</b>이라는 것.
 * 거기에 파일을 만들 수 있는 사람은 이미 {@code worknote.db}를 직접 열어 고칠 수 있으므로 새 권한이 생기지 않는다.
 * 그래서 HTTP 엔드포인트는 <b>절대</b> 두지 않는다 — 하나라도 네트워크로 닿는 순간 이 논거가 통째로 무너진다.
 *
 * <p><b>그 전제를 주장하지 않고 검사한다</b>({@link #violation}). 검사 없이 주장만 하면 지원되는 설정에서
 * 그냥 거짓이 된다: {@code WORKNOTE_STORAGE_STRICT=false}면 server 모드도 755 부모로 뜰 수 있고,
 * 그러면 600 DB를 <b>읽지도 못하는</b> 다른 로컬 사용자가 센티넬만 놓아 관리자 계정을 가져간다 — 새 권한이 생긴 것이다.
 * 나중에 {@code chmod 700}으로 조여도 미리 심어둔 파일은 지워지지도, 소유자가 바뀌지도 않는다(그래서 소유자까지 본다).
 * 이 검사는 {@code worknote.storage.strict}와 <b>무관하게</b> 항상 fail-closed다 — 그 스위치는 저장소 하드닝
 * 경고에 대한 운영자의 선택이지, 복구 뒷문을 넓혀도 된다는 뜻이 아니다.
 *
 * <p><b>포맷은 {@link Properties}</b> — 공백·CRLF·주석·인코딩 예외를 우리가 다시 구현하지 않는다.
 * <pre>
 * emp=admin
 * password=(선택 — 새 비밀번호)
 * </pre>
 * Properties 규약이라 값 안의 역슬래시는 이스케이프다({@code \} 를 쓰려면 {@code \\}).
 * 앞뒤 공백 중 <b>앞</b>은 규약상 잘리지만 뒤는 남으므로 비밀번호는 trim하지 않는다 — 운영자가 적은 그대로 쓴다.
 *
 * <p><b>문제가 있으면 전부 {@link IllegalStateException} = 기동 실패다.</b> WARN 후 계속으로 "개선"하지 말 것:
 * 센티넬을 놓고 재기동한 운영자는 콘솔을 보고 있고, 조용히 넘어가면 잠긴 사람은 잠긴 채로 남는다.
 * 파일도 그대로 둬서 고쳐서 다시 띄울 수 있게 한다.
 */
public final class BreakGlassFile {

    /** DB 파일 옆에 놓는 센티넬 이름. 오버라이드 설정은 없다 — 이유는 {@link #locate}. */
    public static final String FILE_NAME = "break-glass";

    /**
     * 크기 상한. 센티넬은 두 줄짜리 파일이라 이보다 클 이유가 없다. 큰 파일은 우리 것이 아니라는 신호이고,
     * 동시에 기동 경로에서 통째로 읽는 입력의 상한이기도 하다.
     */
    public static final int MAX_BYTES = 4096;

    private static final String EMP = "emp";
    private static final String PASSWORD = "password";
    private static final Set<String> KNOWN_KEYS = Set.of(EMP, PASSWORD);

    /** 그룹·타인에게 열려 있음을 판정하는 비트 — 하나라도 켜져 있으면 소유자 전용이 아니다. */
    private static final Set<PosixFilePermission> SHARED_BITS = EnumSet.of(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private BreakGlassFile() {}

    /** password=null이면 비밀번호는 그대로 두고 2FA만 푼다. */
    public record Request(String emp, String password) {}

    /** 출처 판정에 필요한 <b>사실만</b> 담은 값 — 실제 stat은 {@link #verifyProvenance}가 한다(판정은 순수 함수로). */
    public record Provenance(boolean symlink, boolean regularFile, long size,
                             String owner, Set<PosixFilePermission> perms,
                             String parentOwner, Set<PosixFilePermission> parentPerms) {}

    /**
     * 센티넬 경로 — <b>DB 파일의 부모 디렉토리</b> 한 곳뿐이다. 700을 근거로 삼는 곳이 거기이기 때문이다.
     * 인메모리·비-SQLite면 근거로 삼을 디렉토리가 없으므로 null = 기능 자체가 비활성(테스트가 전부 여기 해당).
     *
     * <p>경로 오버라이드 설정을 두지 않는 이유: SQLite는 DB 부모가 쓰기 가능해야 동작하므로 이 위치는 항상 쓸 수 있고,
     * 반대로 임의 경로를 받으면 공용·전체쓰기 디렉토리를 가리키는 오설정 한 번으로 위 전제가 통째로 깨진다.
     * 운영자가 얻는 것은 없고 잃을 수 있는 것은 기능의 성립 근거 전부다.
     */
    public static Path locate(String jdbcUrl) {
        Path db = StoragePermissions.sqliteFile(jdbcUrl);   // URL 파싱은 저장소 하드닝과 같은 출처를 쓴다
        if (db == null) {
            return null;
        }
        Path parent = db.toAbsolutePath().normalize().getParent();
        return parent == null ? null : parent.resolve(FILE_NAME);
    }

    /**
     * 실제 파일에서 사실을 읽어 {@link #violation}에 묻는다. 위반이면 기동 실패.
     *
     * <p>파일은 <b>링크를 따라가지 않고</b> 본다 — 따라가면 검증한 대상과 읽는 대상이 달라진다.
     * 부모 디렉토리는 반대로 실경로로 해석한다: 별도 볼륨을 심링크로 붙이는 정상 배치가 있고
     * (심링크 자체의 권한은 보통 777이라 그걸 보면 의미가 없다), 우리가 알고 싶은 건 실제 디렉토리의 상태다.
     */
    public static void verifyProvenance(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        try {
            PosixFileAttributes attrs = Files.readAttributes(file, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes parentAttrs = Files.readAttributes(parent, PosixFileAttributes.class);
            String problem = violation(new Provenance(
                attrs.isSymbolicLink(), attrs.isRegularFile(), attrs.size(),
                attrs.owner().getName(), attrs.permissions(),
                parentAttrs.owner().getName(), parentAttrs.permissions()), System.getProperty("user.name"));
            if (problem != null) {
                throw fail(file + " 의 출처를 신뢰할 수 없습니다 — " + problem
                    + ". 이 기능은 '700 앱 소유 디렉토리에 파일을 만들 수 있는 사람은 이미 DB를 직접 고칠 수 있다'는"
                    + " 전제 위에서만 성립하므로, 전제가 확인되지 않으면 실행하지 않습니다");
            }
        } catch (IOException e) {
            throw fail(file + " 의 소유자·권한을 확인할 수 없습니다: " + e);
        }
    }

    /**
     * 출처 판정 — 통과면 null, 아니면 운영자가 읽을 수 있는 사유.
     * 요구 조건은 전부 "이 파일을 놓을 수 있었던 사람 = 이미 DB를 고칠 수 있던 사람"을 증명하기 위한 것이다.
     */
    public static String violation(Provenance p, String processUser) {
        if (p.symlink()) {
            return "심볼릭 링크입니다(링크는 따라가지 않습니다)";
        }
        if (!p.regularFile()) {
            return "일반 파일이 아닙니다";
        }
        if (p.size() > MAX_BYTES) {
            return "파일이 너무 큽니다(" + MAX_BYTES + "바이트 이하여야 합니다)";
        }
        if (!p.owner().equals(processUser)) {
            return "파일 소유자가 앱 실행 계정(" + processUser + ")이 아닙니다";
        }
        if (!p.parentOwner().equals(processUser)) {
            return "상위 디렉토리 소유자가 앱 실행 계정(" + processUser + ")이 아닙니다";
        }
        // 파일은 소유자 전용이어야 한다. 정확히 600을 요구하지는 않는다 — 400처럼 더 좁은 것은 보안 성질을
        // 해치지 않는다. 막아야 하는 것은 그룹·타인 비트뿐이다.
        if (p.perms().stream().anyMatch(SHARED_BITS::contains)) {
            return "파일이 그룹/타인에게 열려 있습니다(chmod 600 필요)";
        }
        if (p.parentPerms().stream().anyMatch(SHARED_BITS::contains)) {
            return "상위 디렉토리가 그룹/타인에게 열려 있습니다(chmod 700 필요) — 누구나 이 자리에 파일을 놓을 수 있었다는 뜻입니다";
        }
        return null;
    }

    /** 읽고 검증한다. 반환됐다면 사번이 있고 비밀번호는(있다면) 정책을 통과한 상태다. */
    public static Request read(Path file) {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw fail(file + " 를 읽을 수 없습니다(UTF-8 텍스트여야 합니다): " + e);
        }

        // 오타 키를 무시하면 최악의 실패가 된다 — `pasword=`로 적은 운영자는 비밀번호가 바뀐 줄 알고
        // 다시 잠긴 문 앞에 선다. 그래서 세우되 <b>키 이름은 찍지 않는다</b>: Properties는 '=' 없는 줄을
        // 값 없는 키로 만들기 때문에, 비밀번호만 적힌 줄이 그대로 키 이름이 되고 메시지에 넣으면
        // 비밀번호가 스택트레이스에 박힌다. 오타 진단의 정확도보다 비밀을 안 흘리는 쪽이 먼저다.
        Set<String> unknown = new TreeSet<>(props.stringPropertyNames());
        unknown.removeAll(KNOWN_KEYS);
        if (!unknown.isEmpty()) {
            throw fail(file + " 에 알 수 없는 키가 " + unknown.size() + "개 있습니다 — 모든 줄은 'key=value' 형식이어야 하고,"
                + " 사용하는 키는 emp, password 둘뿐입니다(키 이름은 비밀 유출을 막기 위해 표시하지 않습니다)");
        }

        String emp = props.getProperty(EMP);
        if (emp == null || emp.isBlank()) {
            throw fail(file + " 에 emp 키가 없습니다 — 복구할 사번을 'emp=사번' 한 줄로 적으세요");
        }

        String password = props.getProperty(PASSWORD);
        if (password != null) {
            // 값 없는 password= 를 "미지정"으로 봐주지 않는다 — 운영자는 비밀번호를 바꿨다고 믿게 된다.
            if (password.isBlank()) {
                throw fail(file + " 의 password 값이 비어 있습니다 — 비밀번호를 바꾸지 않으려면 password 줄을 지우세요");
            }
            // 값은 메시지에 절대 싣지 않는다(길이도 알리지 않는다) — 실패 메시지는 콘솔·로그에 남는다.
            if (password.length() < PasswordPolicy.MIN_LENGTH) {
                throw fail(file + " 의 password 가 비밀번호 정책(" + PasswordPolicy.MIN_LENGTH
                    + "자 이상)에 미달합니다 — 약한 비밀번호를 조용히 설정하느니 기동을 세웁니다");
            }
            // 상한을 넘기면 설정은 되지만 로그인 DTO가 막는다 = 복구했다고 믿는 순간 다시 잠긴다.
            if (password.length() > PasswordPolicy.MAX_LENGTH) {
                throw fail(file + " 의 password 가 상한(" + PasswordPolicy.MAX_LENGTH
                    + "자)을 넘습니다 — 설정되더라도 로그인 단계에서 거부되어 다시 잠깁니다");
            }
        }
        return new Request(emp.trim(), password);
    }

    static IllegalStateException fail(String message) {
        return new IllegalStateException("브레이크글래스 복구: " + message);
    }
}
