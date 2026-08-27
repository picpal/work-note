package com.worknote.admin;

import com.worknote.auth.PasswordPolicy;
import com.worknote.config.StoragePermissions;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * 그냥 거짓이 된다. 전제가 실제로 깨지는 형태는 셋이다.
 * <ul>
 *   <li><b>부모가 그룹/타인 쓰기 가능</b>(775·777 등). {@code WORKNOTE_STORAGE_STRICT=false}면 server 모드도
 *       이런 부모로 뜰 수 있고, 그러면 600 DB를 <b>읽지도 못하는</b> 다른 로컬 사용자가 센티넬만 놓아
 *       관리자 계정을 가져간다 — 새 권한이 생긴 것이다. (755는 타인에게 {@code w}를 주지 않으므로 이 공격이
 *       성립하지 않는다. 그래도 부모에는 그룹/타인 비트를 아예 요구하지 않는다 — 700은 값싸고 확실한 선이다.)</li>
 *   <li><b>조상이 그룹/타인 쓰기 가능</b>. 부모가 700이어도 그 위에 쓸 수 있으면 디렉토리를 엔트리째
 *       바꿔치기할 수 있다 — 같은 결과가 부모의 권한을 건드리지 않고 일어난다.</li>
 *   <li><b>조상이 제3자 소유</b>. 쓰기 비트가 없어도 <b>디렉토리 소유자</b>는 자기 디렉토리의 엔트리를 rename할 수
 *       있고(원한다면 먼저 자기 디렉토리를 chmod하면 그만이다), 결과는 위와 같다. 그래서 조상 소유자는
 *       <b>앱 실행 계정 또는 root</b>만 허용한다 — {@code /}, {@code /var}, {@code /var/lib}, root 소유 {@code /data}
 *       같은 흔한 배치는 그대로 통과하고, {@code /home/other/...} 아래 배치만 걸린다.</li>
 * </ul>
 * 나중에 {@code chmod 700}으로 조여도 미리 심어둔 파일은 지워지지도, 소유자가 바뀌지도 않는다(그래서 소유자까지 본다).
 * 이 검사는 {@code worknote.storage.strict}와 <b>무관하게</b> 항상 fail-closed다 — 그 스위치는 저장소 하드닝
 * 경고에 대한 운영자의 선택이지, 복구 뒷문을 넓혀도 된다는 뜻이 아니다.
 *
 * <p><b>검증·읽기·선점은 한 세션에 묶는다</b>({@link #open}). 셋을 각각 경로로 다시 해석하면 검증한 inode와
 * 읽고 옮기는 inode가 달라질 수 있다(TOCTOU). 리눅스 JDK가 주는 {@link SecureDirectoryStream}이 그 창을 닫는다.
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

    /**
     * 조상 소유자로 허용하는 특권 계정. uid 0을 직접 읽는 표준 API가 없어 <b>이름으로</b> 판정한다 —
     * "root라는 이름의 비특권 계정"을 만들 수 있는 사람은 이미 계정을 만드는 사람이라 이 판정 밖에 있다.
     */
    private static final String ROOT = "root";

    /** 그룹·타인에게 열려 있음을 판정하는 비트 — 하나라도 켜져 있으면 소유자 전용이 아니다. */
    private static final Set<PosixFilePermission> SHARED_BITS = EnumSet.of(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    /** 심링크 전개 상한 — 리눅스 {@code ELOOP}와 같은 수. 순환 심링크에서 기동이 도는 것을 막는다. */
    private static final int MAX_SYMLINKS = 40;
    /** 경로 해석 단계 상한 — 위 상한을 빠져나가는 병리적 입력에 대한 두 번째 그물. */
    private static final int MAX_STEPS = 4096;

    private BreakGlassFile() {}

    /** password=null이면 비밀번호는 그대로 두고 2FA만 푼다. */
    public record Request(String emp, String password) {}

    /**
     * 출처 판정에 필요한 <b>사실만</b> 담은 값 — 실제 stat은 {@link #verifyProvenance}·{@link #open}이 한다
     * (판정은 순수 함수로).
     *
     * @param ancestors 부모 <b>위</b>에서 경로 해석이 지나가는 디렉토리들. 부모 자신은 {@code parentOwner}·
     *                  {@code parentPerms}다.
     */
    public record Provenance(boolean symlink, boolean regularFile, long size,
                             String owner, Set<PosixFilePermission> perms,
                             String parentOwner, Set<PosixFilePermission> parentPerms,
                             List<Ancestor> ancestors) {}

    /**
     * 경로 해석이 지나가는 디렉토리 하나의 사실. 판정 근거는 {@link #violation}의 조상 검사 주석에 있다.
     *
     * @param owner      디렉토리 자신의 소유자. 자기 디렉토리의 엔트리는 소유자가 언제든 rename할 수 있으므로
     *                   쓰기 비트만으로는 부족하다.
     * @param sticky     sticky 비트(0o1000). POSIX 뷰에 없어 raw mode로만 읽히므로, 읽지 못하면 false다(= 더 엄격한 쪽).
     * @param entry      이 디렉토리 안에서 <b>우리가 지나가는 엔트리</b>의 이름(디렉토리·심링크).
     * @param entryOwner 그 엔트리의 소유자(lstat — 심링크면 링크 자신의 소유자). sticky 예외를 따질 때만 쓴다:
     *                   sticky는 "누구도 rename 못한다"가 아니라 "엔트리 소유자·디렉토리 소유자·특권자만
     *                   rename한다"이기 때문이다. 알 수 없으면 null(= 예외를 인정하지 않는다).
     */
    public record Ancestor(String path, String owner, Set<PosixFilePermission> perms, boolean sticky,
                           String entry, String entryOwner) {}

    /**
     * 센티넬 한 번의 소비 — <b>출처 검증·읽기·선점을 같은 디렉토리 핸들에 묶는다</b>({@link #open} 참조).
     * 열렸다는 것은 출처 검증을 통과했다는 뜻이다.
     */
    public interface Sentinel extends AutoCloseable {

        /** 읽고 검증한다. 반환됐다면 사번이 있고 비밀번호는(있다면) 정책을 통과한 상태다. */
        Request read();

        /** 원자적 선점 — 이 rename이 성공한 프로세스만 수술한다. */
        void claim(Path processing);

        /** 열린 디렉토리 핸들에 묶였는가(리눅스) 아니면 경로 기반 폴백인가(macOS 등). 관측·문서용. */
        boolean handleBound();

        @Override
        void close();
    }

    /**
     * 센티넬 경로 — <b>DB 파일의 부모 디렉토리</b> 한 곳뿐이다. 700을 근거로 삼는 곳이 거기이기 때문이다.
     * 인메모리·비-SQLite면 근거로 삼을 디렉토리가 없으므로 null = 기능 자체가 비활성(테스트가 전부 여기 해당).
     *
     * <p>경로 오버라이드 설정을 두지 않는 이유: SQLite는 DB 부모가 쓰기 가능해야 동작하므로 이 위치는 항상 쓸 수 있고,
     * 반대로 임의 경로를 받으면 공용·전체쓰기 디렉토리를 가리키는 오설정 한 번으로 위 전제가 통째로 깨진다.
     * 운영자가 얻는 것은 없고 잃을 수 있는 것은 기능의 성립 근거 전부다.
     *
     * <p>{@code normalize()}는 <b>쓰지 않는다</b> — {@code StoragePermissions.hardenDb}와 같은 관례다.
     * 렉시컬 정규화는 {@code ..}가 심링크를 지나갈 때 OS 해석과 갈리므로(예: {@code link -> /mnt/x/sub}이면
     * {@code link/../db}는 OS에겐 {@code /mnt/x/db}, 렉시컬로는 형제 {@code db}), 정규화하면 운영자가
     * <b>실제 DB 옆</b>에 만든 파일을 앱은 다른 디렉토리에서 찾는다 — 조용히 무시되거나, 더 나쁘게는
     * 그 렉시컬 경로에 남아 있던 무관한 파일이 실행된다. 정규화는 실경로 해석에 맡긴다.
     */
    public static Path locate(String jdbcUrl) {
        Path db = StoragePermissions.sqliteFile(jdbcUrl);   // URL 파싱은 저장소 하드닝과 같은 출처를 쓴다
        if (db == null) {
            return null;
        }
        Path parent = db.toAbsolutePath().getParent();
        return parent == null ? null : parent.resolve(FILE_NAME);
    }

    /**
     * 출처를 검증하고 소비 세션을 연다. 통과하지 못하면 {@link IllegalStateException} = 기동 실패다.
     *
     * <p><b>왜 세션인가.</b> 검증·읽기·선점을 각각 경로로 다시 해석하면, 셋 사이에 부모 디렉토리나 파일이
     * 갈아끼워질 수 있다 — 검증한 inode와 읽고 옮기는 inode가 다른 고전적 TOCTOU다. 리눅스 JDK의
     * {@link Files#newDirectoryStream}은 {@link SecureDirectoryStream}을 돌려주므로, <b>열린 디렉토리 핸들</b>
     * 기준으로 lstat({@code NOFOLLOW_LINKS})·읽기·상대 rename을 할 수 있다. 그러면 셋이 같은 디렉토리·같은
     * 엔트리를 가리키는 것이 커널 수준에서 보장된다.
     *
     * <p><b>미지원 제공자에서는 경로 기반으로 폴백한다</b>(이 클래스의 다른 실패가 전부 기동 중단인 것과 다르다).
     * macOS JDK는 {@code sun.nio.fs.UnixDirectoryStream}을 돌려줘 {@link SecureDirectoryStream}이 아니다 —
     * 거기서 기동을 세우면 개발·검증 환경에서 이 기능이 통째로 죽는다. 폴백에서도 <b>판정 규칙은 완전히 같고</b>
     * 달라지는 것은 TOCTOU 창의 폭뿐이다(리눅스 배포에서는 강한 보장, 그 외에서는 경로 기반). 이 사실은
     * 운영자 가이드에도 적혀 있어야 한다 — 조용한 등급 차이는 없다.
     *
     * <p>조상 체인({@link #ancestorsAbove})은 핸들에 묶을 수 없다(층마다 핸들을 여는 API가 없다). 대신
     * <b>조상을 먼저 확인하고</b>, 그 직후 연 핸들이 방금 확인한 그 디렉토리(inode)인지 {@code fileKey}로 맞춰
     * "조상은 A를 봤는데 핸들은 B를 열었다"를 배제한다.
     */
    public static Sentinel open(Path file) {
        Path abs = file.toAbsolutePath();
        Path parent = abs.getParent();
        Path name = abs.getFileName();
        if (parent == null || name == null) {
            throw fail(abs + " 의 부모 디렉토리를 알 수 없습니다");
        }
        DirectoryStream<Path> stream = null;
        try {
            List<Ancestor> ancestors = ancestorsAbove(parent);   // 핸들을 열기 전에 — 아래 fileKey 비교의 기준
            PosixFileAttributes dir = Files.readAttributes(parent, PosixFileAttributes.class);
            stream = Files.newDirectoryStream(parent);
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                PosixFileAttributes opened =
                    secure.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
                if (!Objects.equals(dir.fileKey(), opened.fileKey())) {
                    throw fail(parent + " 가 검사 도중 다른 디렉토리로 바뀌었습니다 — 아무 작업도 하지 않았습니다."
                        + " 데이터 디렉토리를 건드리는 다른 작업이 없는지 확인하고 재기동하세요");
                }
                PosixFileAttributes target = secure
                    .getFileAttributeView(name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
                check(abs, provenance(target, opened, ancestors));
                Sentinel sentinel = new HandleBoundSentinel(secure, name, abs);
                stream = null;   // 핸들의 수명은 이제 세션의 것이다
                return sentinel;
            }
            PosixFileAttributes target = Files.readAttributes(abs, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            check(abs, provenance(target, dir, ancestors));
            return new PathSentinel(abs);
        } catch (IOException e) {
            throw fail(abs + " 의 소유자·권한을 확인할 수 없습니다: " + e);
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * 경로 기반 출처 검증 — {@link #open}의 폴백이자 판정 규칙 자체의 시험 지점.
     *
     * <p>파일은 <b>링크를 따라가지 않고</b> 본다 — 따라가면 검증한 대상과 읽는 대상이 달라진다.
     * 부모 디렉토리는 반대로 실경로로 해석한다: 별도 볼륨을 심링크로 붙이는 정상 배치가 있고
     * (심링크 자체의 권한은 보통 777이라 그걸 보면 의미가 없다), 우리가 알고 싶은 건 실제 디렉토리의 상태다.
     * 부모 위의 체인은 {@link #ancestorsAbove}가 모은다 — 부모의 700은 그 위에서 통째로
     * 바꿔치기당하는 것을 막지 못하기 때문이다(판정은 {@link #violation}).
     */
    public static void verifyProvenance(Path file) {
        Path abs = file.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent == null) {
            throw fail(abs + " 의 부모 디렉토리를 알 수 없습니다");
        }
        try {
            // 조상 체인이 먼저다 — 순환 심링크는 여기서 상한에 걸려 이유가 붙은 채로 멈춘다.
            List<Ancestor> ancestors = ancestorsAbove(parent);
            PosixFileAttributes dir = Files.readAttributes(parent, PosixFileAttributes.class);
            PosixFileAttributes target = Files.readAttributes(abs, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            check(abs, provenance(target, dir, ancestors));
        } catch (IOException e) {
            throw fail(abs + " 의 소유자·권한을 확인할 수 없습니다: " + e);
        }
    }

    private static Provenance provenance(PosixFileAttributes file, PosixFileAttributes dir,
            List<Ancestor> ancestors) {
        return new Provenance(file.isSymbolicLink(), file.isRegularFile(), file.size(),
            file.owner().getName(), file.permissions(),
            dir.owner().getName(), dir.permissions(), ancestors);
    }

    private static void check(Path file, Provenance p) {
        String problem = violation(p, System.getProperty("user.name"));
        if (problem != null) {
            throw fail(file + " 의 출처를 신뢰할 수 없습니다 — " + problem
                + ". 이 기능은 '700 앱 소유 디렉토리에 파일을 만들 수 있는 사람은 이미 DB를 직접 고칠 수 있다'는"
                + " 전제 위에서만 성립하므로, 전제가 확인되지 않으면 실행하지 않습니다");
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
        // 부모의 700만으로는 부족하다. 경로 해석이 지나가는 디렉토리 중 하나라도 제3자가 통제하면,
        // 그 사람이 데이터 디렉토리를 엔트리째 rename하고 같은 이름의 자기 소유 700 디렉토리로 바꿔치기할 수 있다.
        // 자식의 권한은 자기 엔트리가 부모 안에서 rename되는 것을 막지 못한다 — 그러면 DB를 읽지도 못하던
        // 사람이 자기 센티넬을 "700 앱 소유 디렉토리"에 놓은 것처럼 보이게 만들 수 있다.
        for (Ancestor a : p.ancestors()) {
            String problem = ancestorViolation(a, processUser);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * 조상 하나의 판정. 통제 주체는 둘이다 — <b>소유자</b>(엔트리를 언제든 rename할 수 있다)와
     * <b>쓰기 비트를 가진 사람</b>.
     *
     * <p>소유자는 앱 계정·root만 허용한다. 쓰기 비트가 없어도 디렉토리 소유자는 자기 디렉토리를 chmod한 뒤
     * rename하면 그만이라, 755·700이라는 사실이 제3자 소유 디렉토리를 안전하게 만들어 주지 못한다.
     *
     * <p>쓰기 비트는 그룹/타인만 본다. 부모에 적용하는 "그룹/타인 비트가 하나라도 있으면 거부"를 여기까지
     * 확대하면 {@code /}, {@code /srv}, {@code /var/lib} 같은 정상적인 755 조상이 전부 걸려 멀쩡한 배포가
     * 기동 불가가 된다. 막아야 하는 건 "엔트리를 바꿔치기할 수 있는가" 하나뿐이다.
     *
     * <p>sticky(예: {@code /tmp}의 1777)는 <b>조건부</b> 예외다. sticky는 "누구도 rename하지 못한다"가 아니라
     * "엔트리 소유자·디렉토리 소유자·특권자만 rename한다"이므로, 우리가 지나가는 엔트리가 남의 것이면
     * (예: {@code /tmp/link}가 공격자 소유 심링크) 그 사람은 sticky 아래에서도 검증 직후 갈아끼울 수 있다.
     * 그래서 엔트리까지 앱 계정·root 소유일 때만 인정한다 — {@code /tmp} 아래에 앱 소유 디렉토리를 두는
     * 정상 배치는 통과하고, 공격자 소유 엔트리를 지나가는 배치는 걸린다.
     */
    private static String ancestorViolation(Ancestor a, String processUser) {
        if (!trusted(a.owner(), processUser)) {
            return "상위 경로 " + a.path() + " 의 소유자가 " + a.owner() + " 입니다(앱 실행 계정 " + processUser
                + " 또는 root여야 합니다) — 디렉토리 소유자는 그 안의 엔트리를 언제든 rename할 수 있으므로"
                + " 데이터 디렉토리를 통째로 자기 것으로 바꿔치기할 수 있고, 그러면 '700 부모'가 근거가 되지 못합니다"
                + "(조치: 데이터 디렉토리를 앱 계정 또는 root 소유 경로 아래로 옮기세요 — 예: /var/lib/worknote)";
        }
        boolean writableByOthers = a.perms().contains(PosixFilePermission.GROUP_WRITE)
            || a.perms().contains(PosixFilePermission.OTHERS_WRITE);
        if (!writableByOthers) {
            return null;
        }
        if (a.sticky() && trusted(a.entryOwner(), processUser)) {
            return null;   // sticky + 우리 엔트리 — 남이 이 엔트리를 rename·삭제할 수 없다
        }
        return "상위 경로 " + a.path() + " 가 그룹/타인에게 쓰기 가능합니다(현재 "
            + PosixFilePermissions.toString(a.perms()) + (a.sticky() ? " + sticky" : "") + ") — 거기에 쓸 수 있는"
            + " 사람은 데이터 디렉토리를 통째로 자기 것으로 바꿔치기할 수 있으므로 '700 부모'가 근거가 되지 못합니다"
            + (a.sticky()
                ? "(sticky지만 그 안에서 지나가는 엔트리 '" + a.entry() + "' 가 앱 실행 계정 소유가 아니라 "
                    + a.entryOwner() + " 입니다 — sticky는 엔트리 소유자의 rename을 막지 않습니다)"
                : "(조치: chmod g-w,o-w " + a.path() + " — 다만 그 디렉토리가 WorkNote 전용이 아니면 권한을 조이지 말고"
                    + " 데이터 디렉토리를 전용 경로로 옮기세요)");
    }

    private static boolean trusted(String owner, String processUser) {
        return owner != null && (owner.equals(processUser) || owner.equals(ROOT));
    }

    /**
     * 부모 <b>위</b>에서 경로 해석이 지나가는 디렉토리 전부 — 커널이 실제로 거치는 것과 같은 순서로 한 컴포넌트씩
     * 해석하면서, <b>심링크를 만날 때마다 그 타깃의 조상 체인까지</b> 이어서 순회한다.
     *
     * <p>렉시컬 부모 체인과 최종 실경로 체인 둘만 훑으면 <b>중간 심링크 타깃의 조상</b>이 통째로 빠진다.
     * <pre>
     * /safe/l1   -> /open/a          렉시컬 체인은 여기서 /open/a 로 점프한다 — /open 은 보지 않는다
     * /open/a/l2 -> /secure/final    최종 실경로 체인은 /secure/... 만 올라간다
     * </pre>
     * {@code /open}이 777이면 검증 후 {@code /open/a}를 자기 트리로 바꿔치기할 수 있고, 그 뒤의 읽기·rename은
     * 공격자 파일을 집는다. 그래서 "커널이 지나가는 디렉토리 전부"가 기준이다.
     *
     * <p>기록 단위는 <b>(디렉토리, 그 안에서 지나가는 엔트리)</b>다 — sticky 예외가 엔트리 소유자에 달려 있고
     * (POSIX sticky는 엔트리 소유자의 rename을 막지 않는다), 한 디렉토리를 서로 다른 엔트리로 두 번 지나갈 수도
     * 있기 때문이다. 디렉토리 권한은 이미 해석된 실경로에서 읽고, 엔트리 소유자는 링크를 따라가지 않고 읽는다.
     *
     * <p>순환 심링크는 {@link #MAX_SYMLINKS}·{@link #MAX_STEPS}로 막는다 — 무한 루프는 곧 기동 행이다.
     * 도중에 stat이 실패하면 {@link IOException}이 그대로 올라가 기동 실패가 된다(fail-closed).
     */
    static List<Ancestor> ancestorsAbove(Path parent) throws IOException {
        Path abs = parent.toAbsolutePath();
        Path cur = abs.getRoot();
        if (cur == null) {
            throw new IOException("절대 경로가 아닙니다: " + parent);
        }
        Deque<String> pending = new ArrayDeque<>();
        pushAll(pending, abs);
        Map<String, Ancestor> found = new LinkedHashMap<>();
        int symlinks = 0;
        int steps = 0;
        while (!pending.isEmpty()) {
            if (++steps > MAX_STEPS) {
                throw new IOException("경로 해석이 " + MAX_STEPS + "단계를 넘었습니다(심링크 순환일 수 있습니다): " + parent);
            }
            String name = pending.removeFirst();
            if (name.isEmpty() || ".".equals(name)) {
                continue;
            }
            if ("..".equals(name)) {
                Path up = cur.getParent();
                cur = up == null ? cur : up;   // 루트의 위는 루트다(POSIX)
                continue;
            }
            Path entry = cur.resolve(name);
            PosixFileAttributes entryAttrs =
                Files.readAttributes(entry, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            record(found, cur, name, entryAttrs.owner().getName());
            if (entryAttrs.isSymbolicLink()) {
                if (++symlinks > MAX_SYMLINKS) {
                    throw new IOException("심링크가 " + MAX_SYMLINKS + "단계를 넘습니다(순환일 수 있습니다): " + parent);
                }
                Path target = Files.readSymbolicLink(entry);
                if (target.isAbsolute()) {
                    cur = target.getRoot();
                }
                pushAll(pending, target);   // 타깃의 조상 체인도 이어서 지나간다 — 여기가 빠지면 위 반례가 뚫린다
            } else {
                cur = entry;
            }
        }
        // 마지막으로 도달한 cur이 최종 부모다. 부모 자신은 parentOwner·parentPerms로 따로 판정한다.
        return List.copyOf(found.values());
    }

    private static void pushAll(Deque<String> pending, Path path) {
        for (int i = path.getNameCount() - 1; i >= 0; i--) {
            pending.addFirst(path.getName(i).toString());
        }
    }

    /** (디렉토리, 엔트리) 한 쌍당 한 번만 stat한다 — 같은 쌍을 두 번 지나가는 경로가 있다. */
    private static void record(Map<String, Ancestor> found, Path dir, String entry, String entryOwner)
            throws IOException {
        String key = dir + " " + entry;
        if (found.containsKey(key)) {
            return;
        }
        PosixFileAttributes attrs = Files.readAttributes(dir, PosixFileAttributes.class);
        found.put(key, new Ancestor(dir.toString(), attrs.owner().getName(), attrs.permissions(),
            sticky(dir), entry, entryOwner));
    }

    /**
     * sticky 비트(0o1000). {@link PosixFilePermission}에는 sticky가 없어서 JDK Unix 제공자의 raw mode로만 읽힌다 —
     * POSIX 표준 뷰가 아니므로 읽지 못하는 환경이 있을 수 있고, 그때는 <b>없는 것으로 본다</b>(더 엄격한 쪽).
     */
    private static boolean sticky(Path dir) {
        try {
            return Files.getAttribute(dir, "unix:mode") instanceof Integer mode && (mode & 01000) != 0;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 읽고 검증한다(경로 기반 — {@link Sentinel#read()}의 폴백). 반환됐다면 사번이 있고 비밀번호는(있다면)
     * 정책을 통과한 상태다.
     */
    public static Request read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file);
        } catch (IOException e) {
            throw fail(file + " 를 읽을 수 없습니다(UTF-8 텍스트여야 합니다): " + e);
        }
    }

    /** 파싱·검증 — 바이트를 어디서 읽었는지와 무관한 부분(경로 기반이든 핸들 기반이든 같은 규칙). */
    private static Request parse(String text, Path file) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
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

    /** 선점 실패 메시지는 두 구현이 공유한다 — 운영자가 보는 문구가 제공자에 따라 달라질 이유가 없다. */
    private static IllegalStateException claimFailed(Path file, Path processing, Exception cause) {
        return fail(file + " 을(를) " + processing + " 로 옮기지 못했습니다(" + cause + ")."
            + " 옮기지 못한 채로 진행하면 다음 기동이 같은 파일을 다시 적용합니다 — 디렉토리 쓰기 권한을 확인하세요");
    }

    /**
     * 리눅스 경로 — 열린 디렉토리 핸들 기준으로 읽고 옮긴다. 경로를 다시 해석하지 않으므로
     * {@link #open}이 검증한 그 엔트리를 읽고 그 엔트리를 옮기는 것이 커널 수준에서 보장된다.
     */
    private record HandleBoundSentinel(SecureDirectoryStream<Path> dir, Path name, Path file) implements Sentinel {

        @Override
        public Request read() {
            try (SeekableByteChannel channel = dir.newByteChannel(name,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                return parse(decode(channel, file), file);
            } catch (IOException e) {
                throw fail(file + " 를 읽을 수 없습니다(UTF-8 텍스트여야 합니다): " + e);
            }
        }

        @Override
        public void claim(Path processing) {
            try {
                dir.move(name, dir, processing.getFileName());   // 같은 핸들 안의 상대 rename = 원자적
            } catch (IOException e) {
                throw claimFailed(file, processing, e);
            }
        }

        @Override
        public boolean handleBound() {
            return true;
        }

        @Override
        public void close() {
            closeQuietly(dir);
        }
    }

    /** {@link SecureDirectoryStream} 미지원 제공자(macOS 등)의 폴백 — 판정 규칙은 같고 TOCTOU 창만 넓다. */
    private record PathSentinel(Path file) implements Sentinel {

        @Override
        public Request read() {
            return BreakGlassFile.read(file);
        }

        @Override
        public void claim(Path processing) {
            try {
                Files.move(file, processing, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw claimFailed(file, processing, e);
            }
        }

        @Override
        public boolean handleBound() {
            return false;
        }

        @Override
        public void close() {
            // 잡고 있는 자원이 없다
        }
    }

    /** 상한을 넘는 바이트는 읽지 않는다 — 크기 검증은 이미 지났지만, 읽는 쪽에도 같은 상한을 둔다. */
    private static String decode(SeekableByteChannel channel, Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // 채워질 때까지
        }
        if (!buffer.hasRemaining()) {
            throw fail(file + " 이(가) 너무 큽니다(" + MAX_BYTES + "바이트 이하여야 합니다)");
        }
        buffer.flip();
        return StandardCharsets.UTF_8.newDecoder().decode(buffer).toString();   // 기본 REPORT — 깨진 UTF-8은 예외
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // 닫기 실패로 복구를 세우지 않는다 — 이미 할 일은 끝났거나, 세울 이유는 따로 던져진 뒤다.
        }
    }

    static IllegalStateException fail(String message) {
        return new IllegalStateException("브레이크글래스 복구: " + message);
    }
}
