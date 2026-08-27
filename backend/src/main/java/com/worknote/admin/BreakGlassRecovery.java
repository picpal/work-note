package com.worknote.admin;

import com.worknote.audit.AuditService;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.totp.TotpService;
import com.worknote.config.StoragePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 폐쇄망 최후 복구 — DB 옆에 놓인 센티넬 파일 하나로 잠긴 계정의 2FA를 풀고(선택) 비밀번호를 재설정한다.
 *
 * <p><b>왜 필요한가.</b> 잠긴 계정을 되살리는 기존 경로는 {@code RecoveryService}(이메일)뿐인데
 * {@code WORKNOTE_SMTP_HOST}는 선택 설정이다. SMTP 없는 배치에서 유일한 관리자가 2FA를 켠 뒤 휴대폰을 잃으면
 * 아무도 로그인할 수 없고, 로그인할 수 없으니 아무도 초기화해 줄 수 없다 — 남는 방법은 SQLite 손편집뿐이다.
 * 이 클래스는 그 손편집을 <b>원자적이고 감사되는 한 번</b>으로 바꾼다.
 *
 * <p><b>왜 뒷문이 아닌가.</b> 트리거가 700(앱 계정 소유) 디렉토리에 파일을 만들 수 있는 능력이기 때문이다 —
 * 그 능력이 있으면 이미 DB를 직접 고칠 수 있다. 이 성질을 지키는 규칙:
 * <ul>
 *   <li><b>HTTP 표면 0</b> — 컨트롤러도, 엔드포인트도 없다. 네트워크로 닿는 순간 위 논거가 무너진다.</li>
 *   <li><b>전제를 검사한다</b> — 소유자·권한·형태를 매번 확인한다({@link BreakGlassFile#violation}).
 *       주장만 하고 검사하지 않으면 지원되는 설정에서 그냥 거짓이 된다. 확인할 수 없으면 실행하지 않는다.</li>
 *   <li><b>1회용</b> — 처리 전에 {@code .processing}으로 옮기고 커밋 후 지운다(아래).</li>
 *   <li><b>시끄럽다</b> — 감사 행 + WARN. 조용히 일어날 수 있으면 안 된다.</li>
 * </ul>
 *
 * <p><b>왜 지우기 전에 옮기는가.</b> 커밋과 삭제 사이에 프로세스가 죽으면 계정은 이미 바뀌었는데 센티넬은
 * 그대로 남는다 — 다음 기동이 <b>조용히 다시 적용</b>한다. 그 사이 당사자가 2FA를 재등록했거나 비밀번호를
 * 바꿨다면 그것을 말없이 되돌린다. 그래서 트랜잭션을 열기 <b>전에</b> {@code break-glass.processing}으로
 * 원자적 rename(=선점)하고, 커밋 뒤에 지운다. 기동 시 {@code .processing}이 남아 있으면 이전 시도가 중단된
 * 것이므로 <b>기동을 세운다</b> — 적용됐는지 우리도 모르고, 모르는 채로 추측하는 것보다 말하는 편이 낫다.
 * (덤으로 같은 DB를 향해 두 프로세스가 뜨면 rename 승자 하나만 실행한다)
 *
 * <p>대상은 관리자로 제한하지 않는다. 호스트 접근 권한자는 어차피 무엇이든 할 수 있으므로 제한은 보안이 아니라
 * 겉치레이고, 문서화된 용도는 관리자 락아웃이다. 다음 로그인에서 비밀번호 변경을 강제하지도 않는다 —
 * 비밀번호를 고른 사람이 곧 운영자다.
 *
 * <p>실행 시점은 {@link ApplicationStartedEvent} — Flyway·DataSource가 끝난 뒤다
 * ({@code StoragePermissionGuard}의 후반 패스와 같은 이유·같은 배선). 참고로 {@code AdminBootstrap}은
 * {@code ApplicationRunner}라 이보다 <b>뒤</b>에 돈다. 즉 사용자가 하나도 없는 최초 기동에 센티넬이 있으면
 * "사번 없음"으로 기동이 멈추는데, 복구할 계정 자체가 없는 상황이므로 그게 맞는 결론이다.
 */
@Component
public class BreakGlassRecovery implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassRecovery.class);

    private static final String ACTIVE = "active";
    /** 감사 who — 세션 사용자가 아니라 호스트 운영자가 한 일임을 한눈에 구분하려고 사번 대신 고정 문자열을 쓴다. */
    private static final String ACTOR = "break-glass";
    /** 처리 중 표식 — 이 이름으로 옮겨진 순간 그 시도는 우리 것이다. */
    static final String PROCESSING_SUFFIX = ".processing";

    private final UserMapper users;
    private final UserAdminService admin;
    private final TotpService totp;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final String jdbcUrl;
    private final boolean serverMode;

    public BreakGlassRecovery(UserMapper users, UserAdminService admin, TotpService totp,
                              AuditService audit, TransactionTemplate tx,
                              @Value("${spring.datasource.url:}") String jdbcUrl,
                              @Value("${worknote.mode:local}") String mode) {
        this.users = users;
        this.admin = admin;
        this.totp = totp;
        this.audit = audit;
        this.tx = tx;
        this.jdbcUrl = jdbcUrl;
        this.serverMode = "server".equals(mode);
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        run(BreakGlassFile.locate(jdbcUrl));
    }

    /**
     * 센티넬 소비 전 과정. 파일이 없으면(평상시 전부) 아무 일도 하지 않는다.
     *
     * <p>server 모드의 실패는 전부 던진다 = 기동 실패. WARN 후 계속으로 바꾸지 말 것 — 센티넬을 놓고
     * 재기동한 운영자는 콘솔을 보고 있고, 조용히 넘어가면 잠긴 사람은 잠긴 채로 남는다. 파일도 남겨
     * 고쳐서 다시 띄우게 한다. 유일한 예외는 local 모드(아래) — 거기선 애초에 아무것도 하지 않는다.
     */
    void run(Path sentinel) {
        if (sentinel == null) {
            return;   // 인메모리·비-SQLite — 근거로 삼을 디렉토리가 없다
        }
        Path processing = sentinel.resolveSibling(sentinel.getFileName() + PROCESSING_SUFFIX);
        if (!serverMode) {
            cleanUpInLocalMode(sentinel, processing);
            return;
        }
        boolean hasSentinel = present(sentinel);
        boolean hasProcessing = present(processing);
        if (!hasSentinel && !hasProcessing) {
            return;
        }
        if (!StoragePermissions.posixSupported(sentinel)) {
            // 소유자·권한을 확인할 수 없는 파일시스템에서는 이 기능의 전제를 증명할 방법이 없다.
            // 증명할 수 없으면 실행하지 않는다 — 파일도 건드리지 않는다(우리 것이라고 단정할 근거조차 없다).
            log.warn("브레이크글래스 센티넬을 발견했지만 POSIX 권한을 확인할 수 없는 파일시스템이라 기능을 비활성화합니다: {}."
                + " 이 플랫폼에서는 DB를 직접 편집하는 기존 절차를 사용하세요.", sentinel);
            return;
        }
        if (hasProcessing) {
            // 적용됐는지 알 수 없다 — 추측하고 다시 돌리면 그 사이의 재등록·비밀번호 변경을 말없이 되돌린다.
            throw BreakGlassFile.fail("이전 복구 시도가 중단된 흔적이 있습니다: " + processing
                + ". 적용 여부가 불확실하므로 자동으로 재시도하지 않습니다 — 해당 계정 상태(2FA·비밀번호·감사 로그)를"
                + " 확인하고 이 파일을 지운 뒤 재기동하세요");
        }
        // 검증·읽기·선점·정리를 한 세션(리눅스에서는 열린 디렉토리 핸들)에 묶는다 — 각각 경로로 다시 해석하면
        // 검증한 inode와 읽고·옮기고·지우는 inode가 달라질 수 있다. 여는 것 자체가 출처 검증이다(BreakGlassFile.open).
        // 세션이 트랜잭션 바깥까지 열려 있는 것은 의도다: 정리(삭제)까지 같은 디렉토리 핸들에서 해야 하기 때문이고,
        // 기동 중 한 번뿐인 이 경로에서 디렉토리 fd 하나를 잠깐 더 들고 있는 비용은 없다.
        try (BreakGlassFile.Sentinel session = BreakGlassFile.open(sentinel)) {
            BreakGlassFile.Request req = session.read();   // 검증이 수술보다 먼저 — 반쯤 적용 금지
            requireKnownEmp(req.emp());    // 선점 전에 사번까지 확인 — 오타 하나로 .processing이 남아 다음 기동까지 막지 않게
            session.claim(processing);     // 선점 후 수술 — 재적용 가능한 창을 없앤다
            Outcome outcome = tx.execute(status -> apply(req));
            // 삭제보다 먼저 기록한다 — 삭제에 실패해 기동이 멈춰도 "무엇이 일어났는지"는 남아야 한다.
            log.warn("브레이크글래스 복구를 실행했습니다 — 사번 {}: 2FA 해제·유예 재시작{}{}. 처리 파일을 정리합니다.",
                outcome.emp(),
                outcome.passwordReset() ? " / 비밀번호 재설정(기존 세션 무효화)" : "",
                outcome.reactivated() ? " / 계정 상태 active로 복구" : "");
            delete(session, processing);
        }
    }

    /**
     * local 모드엔 인증 자체가 없다 — 되살릴 로그인이 없는데 계정을 건드리는 건 근거 없는 수술이다.
     * 내용도 읽지 않고 출처도 따지지 않는다(파싱·권한 문제로 개인 PC를 세울 이유가 없다).
     *
     * <p>그래도 지우는 이유: 이 파일엔 평문 비밀번호가 들어 있을 수 있고, 남겨두면 나중에 server 모드로
     * 전환한 순간 운영자가 잊은 파일이 그때 발동한다. 파일의 존재 이유는 소비되는 것뿐이다.
     * 존재 확인도 느슨한 {@link Files#exists}로 충분하다 — server 모드와 달리 "알 수 없음"이 걸린 게 없다.
     */
    private void cleanUpInLocalMode(Path sentinel, Path processing) {
        for (Path path : new Path[] {sentinel, processing}) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            log.warn("브레이크글래스 파일을 발견했지만 local 모드(무인증)라 무시하고 삭제합니다: {}", path);
            try {
                Files.delete(path);
            } catch (IOException e) {
                // 여기서만 WARN으로 끝낸다 — 아무 수술도 하지 않았으니 재무장할 뒷문이 없고,
                // 개인 PC를 파일 하나 못 지웠다고 세우지 않는다(StoragePermissionGuard의 local 정책과 같은 결).
                log.warn("브레이크글래스 파일을 삭제하지 못했습니다 — 직접 지우세요: {} ({})", path, e.toString());
            }
        }
    }

    /**
     * 존재 확인. {@link Files#exists}를 쓰지 않는 이유: 그건 접근 오류에도 false를 돌려줘
     * "읽을 수 없는 센티넬"을 "센티넬 없음"으로 둔갑시킨다 — 읽을 수 없으면 세운다는 약속과 정반대다.
     * 없음으로 해석하는 실패는 {@link NoSuchFileException} 하나뿐이다.
     */
    private static boolean present(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw BreakGlassFile.fail(path + " 의 존재 여부를 확인할 수 없습니다(" + e + ")"
                + " — 없는 것으로 넘기면 복구를 요청한 운영자가 아무 설명 없이 잠긴 채로 남습니다");
        }
    }

    /** 선점 전 사번 확인. 최종 판단은 트랜잭션 안({@link #apply})이고, 여기는 흔한 오타를 값싸게 되돌리기 위한 것이다. */
    private void requireKnownEmp(String emp) {
        if (users.findByEmp(emp) == null) {
            throw BreakGlassFile.fail("사번 '" + emp + "' 사용자가 없습니다 — 파일을 고쳐 다시 기동하세요");
        }
    }

    private record Outcome(String emp, boolean passwordReset, boolean reactivated) {}

    /** 한 트랜잭션 — 다음 로그인을 막는 것들을 한꺼번에 걷어낸다. 하나라도 실패하면 전부 되돌린다. */
    private Outcome apply(BreakGlassFile.Request req) {
        UserRow user = users.findByEmp(req.emp());
        if (user == null) {
            throw BreakGlassFile.fail("사번 '" + req.emp() + "' 사용자가 없습니다 — 파일을 고쳐 다시 기동하세요");
        }
        // 관리자 2FA 초기화(2fa.admin.reset)와 같은 경로: 시드 폐기 + 미사용 복구코드 무효화 + 유예 재시작.
        // 유예 재시작이 빠지면 만료된 admin은 로그인만 되고 관리 API는 계속 403이라 잠긴 채로 남는다.
        totp.reset(user.id());
        admin.resetGrace(user.id());

        boolean passwordReset = req.password() != null;
        if (passwordReset) {
            // 해싱·세션 무효화(salt 교체 → AuthFilter가 기존 세션 차단)를 다시 구현하지 않고 관리자 경로를 그대로 쓴다.
            admin.resetPassword(user.id(), req.password());
        }
        boolean reactivated = !ACTIVE.equals(user.status());
        if (reactivated) {
            // 비활성·승인대기 계정은 비밀번호가 맞아도 로그인이 막힌다 — 다음 시도를 막는 것은 전부 걷어낸다.
            admin.update(null, user.id(), null, null, null, ACTIVE);
        }
        // 시도 제한(AuthRateLimiter)은 프로세스 인메모리라 재기동으로 이미 비어 있다 — 여기서 손댈 것이 없다.
        audit.logRaw(ACTOR, "auth.break_glass", user.emp(), null);
        return new Outcome(user.emp(), passwordReset, reactivated);
    }

    /**
     * 커밋 뒤 정리 — <b>선점과 같은 세션</b>으로 지운다. 밖에서 rename만 되고 unlink만 실패하는 상태를 만들 수 없어
     * 테스트가 직접 호출한다.
     *
     * <p>세션에 묶는 이유: 경로로 다시 해석해 지우면 그 사이 디렉토리가 갈아끼워졌을 때 <b>엉뚱한 디렉토리의</b>
     * {@code .processing}을 지우게 되고, 그러면 진짜 {@code .processing}이 남아도 다음 기동은 같은 경로(=갈아끼워진
     * 쪽)를 보므로 그것을 보지 못한다. 즉 "지워도 fail-closed"라는 논증이 조건부가 된다. 핸들 상대 삭제
     * (리눅스)에서는 그 조건 자체가 사라진다 — 우리가 rename해 넣은 <b>그 디렉토리</b>에서 지운다.
     * 경로 폴백(macOS 등)에는 이 보장이 없고, 거기서는 위 논증이 그대로 조건부로 남는다.
     *
     * <p>남는 창: 그 이름이 가리키는 파일이 삭제 직전에 바뀌었을 수 있다. 그때 사라지는 것은 남이 놓은 파일이고
     * 복구는 이미 커밋됐으므로 새로 생기는 권한은 없다 — 검증→읽기→선점이 어긋날 때(검증한 것과 다른 파일을
     * 실행)와 달리 여기서 재적용되는 것은 없다.
     */
    void delete(BreakGlassFile.Sentinel session, Path processing) {
        try {
            session.discard(processing);
        } catch (IOException e) {
            // 남은 .processing은 다음 기동을 세운다(중단 흔적으로 읽히므로). 조용히 넘기면 그 정지가
            // 아무 설명 없이 찾아오므로, 지금 이 자리에서 이유와 함께 세운다.
            throw BreakGlassFile.fail("처리 파일 " + processing + " 을(를) 삭제하지 못했습니다(" + e + ")."
                + " 복구 자체는 이미 적용됐습니다 — 이 파일을 직접 지운 뒤 재기동하세요");
        }
    }
}
