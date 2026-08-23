package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.worknote.audit.AuditService;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.totp.TotpService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 복구 수술은 <b>한 트랜잭션</b>이어야 한다 — 2FA 폐기·복구코드 무효화·유예 재시작·비밀번호 재설정·상태 복구가
 * 반쯤 적용되면 운영자는 "무엇이 적용됐는지" 모르는 계정을 손에 쥔다. 게다가 센티넬은 이미 {@code .processing}으로
 * 선점된 뒤라 다음 기동은 중단되고, 그 정지 앞에서 판단할 근거가 반쪽짜리 상태뿐이게 된다.
 *
 * <p>트랜잭션 경계를 관측하려면 <b>중간에 실패</b>시켜야 한다. 마지막 단계인 감사 기록을 실패시키는 것이
 * 그 앞 단계 전부(2FA·비밀번호·상태)가 되돌아오는지 한 번에 보여 준다 —
 * {@code PermissionAuditAtomicityTest}가 쓰는 것과 같은 수법이다.
 *
 * <p>{@link AuditService}를 목으로 바꾸므로 감사 행을 관측하는 테스트와 같이 둘 수 없어 별도 클래스다
 * (인메모리 DB 이름도 분리한다 — 익명 공유 DB는 JVM 전역이라 다른 컨텍스트와 충돌한다).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-breakglass-atomic?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class BreakGlassAtomicityTest {

    private static final String OLD_PW = "old-place-holder";
    private static final String NEW_PW = "new-place-holder";

    @Autowired BreakGlassRecovery breakGlass;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @MockBean AuditService audit;   // 기본 no-op — auth.break_glass만 doThrow로 실패시킨다

    @TempDir Path dir;

    private String oldHash;

    @BeforeEach
    void seedLockedOutAdmin() throws IOException {
        jdbc.update("DELETE FROM totp_recovery");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("a1", "admin01", null, "관리", "admin", "disabled", null));
        String salt = PasswordHasher.newSalt();
        oldHash = PasswordHasher.hash(OLD_PW, salt);
        users.insertCredential(new CredentialRow("a1", salt, oldHash));
        jdbc.update("INSERT INTO user_totp (user_id, secret_enc, enabled, last_step, created_at) "
            + "VALUES ('a1','enc',1,0,'2026-01-01T00:00:00')");
        jdbc.update("INSERT INTO totp_recovery (id, user_id, salt, code_hash, expires_at, used, created_at) "
            + "VALUES ('rc-1','a1','s','h','2999-01-01T00:00:00',0,'2026-01-01T00:00:00')");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    /** 마지막 단계가 실패하면 그 앞의 수술은 <b>하나도</b> 남지 않아야 한다. */
    @Test
    void aFailureMidWayRollsBackEverySurgicalStep() {
        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).logRaw(any(), eq("auth.break_glass"), any(), any());

        Path file = sentinel("emp=admin01\npassword=" + NEW_PW + "\n");
        assertThatThrownBy(() -> breakGlass.run(file)).isInstanceOf(IllegalStateException.class);

        assertThat(totp.isEnabled("a1")).as("2FA 시드 폐기가 되돌아와야 한다").isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM totp_recovery WHERE user_id='a1' AND used=0", Integer.class))
            .as("복구 코드 무효화가 되돌아와야 한다").isOne();
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM user_credential WHERE user_id='a1'", String.class))
            .as("비밀번호 재설정이 되돌아와야 한다").isEqualTo(oldHash);
        assertThat(users.findById("a1").status())
            .as("계정 상태 변경이 되돌아와야 한다").isEqualTo("disabled");
    }

    /**
     * 실패해도 선점 파일은 남는다 — 이미 rename된 뒤이기 때문이다. 그래서 다음 기동은 중단되고
     * (그 정지는 {@code BreakGlassRecoveryTest}가 증명한다) 운영자가 상태를 확인한 뒤 판단하게 된다.
     * 위 롤백이 있어야 그 확인이 "아무것도 적용되지 않았다"는 단정으로 끝난다.
     */
    @Test
    void theClaimedFileRemainsSoTheNextStartupHalts() {
        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).logRaw(any(), eq("auth.break_glass"), any(), any());

        Path file = sentinel("emp=admin01\n");
        assertThatThrownBy(() -> breakGlass.run(file)).isInstanceOf(IllegalStateException.class);

        assertThat(file).doesNotExist();
        assertThat(file.resolveSibling(file.getFileName() + BreakGlassRecovery.PROCESSING_SUFFIX)).exists();
    }

    /** 운영자가 규정대로 만든 센티넬 — 600, 부모 700. */
    private Path sentinel(String content) {
        try {
            Path file = dir.resolve(BreakGlassFile.FILE_NAME);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
