package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.PasswordPolicy;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.auth.totp.Totp;
import com.worknote.auth.totp.TotpService;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 브레이크글래스 복구의 실제 계정 수술 — server 모드에서 "정말 다시 들어갈 수 있는가"까지 본다.
 *
 * <p>센티넬 경로 유도는 인메모리 DB에서 null이라({@link BreakGlassFileTest}) 여기선 경로를 직접 넘겨
 * {@code run(Path)}를 호출한다. 기동 이벤트 배선과 실제 기동 중 동작은 {@code BreakGlassServerStartupTest}·
 * {@code BreakGlassStartupTest}가 증명한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-breakglass?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
class BreakGlassRecoveryTest {

    private static final String OLD_PW = "old-place-holder";
    private static final String NEW_PW = "new-place-holder";

    @Autowired BreakGlassRecovery breakGlass;
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired TotpService totp;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path dir;

    @BeforeEach
    void seed() throws IOException {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("a1", "admin01", null, "관리", "admin", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("a1", salt, PasswordHasher.hash(OLD_PW, salt)));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    @AfterEach
    void restoreDirPermissions() throws IOException {
        if (Files.exists(dir)) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ---- 없으면 아무 일도 없다 ----

    @Test
    void noSentinel_isCompletelyInert() {
        enable2fa();
        breakGlass.run(null);                    // 경로 자체가 없는 배치(인메모리·비-SQLite)
        breakGlass.run(dir.resolve("nope"));     // 경로는 있으나 파일이 없는 평상시
        assertThat(totp.isEnabled("a1")).isTrue();
        assertThat(auditRows()).isEmpty();
    }

    // ---- 성공 경로 ----

    @Test
    void disables2fa_soTheLockedOutUserCanLogInWithThePasswordAlone() throws Exception {
        enable2fa();
        breakGlass.run(sentinel("emp=admin01\n"));

        assertThat(totp.isEnabled("a1")).isFalse();
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"" + OLD_PW + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emp").value("admin01"));   // 2fa_required가 아니라 완전 인증
    }

    /** 미사용 복구 코드도 함께 폐기돼야 한다 — 시드만 지우면 옛 메일에 남은 코드가 계속 유효하다. */
    @Test
    void disables2fa_alsoInvalidatesOutstandingRecoveryCodes() {
        enable2fa();
        jdbc.update("INSERT INTO totp_recovery (id, user_id, salt, code_hash, expires_at, used, created_at) "
            + "VALUES ('rc-1','a1','s','h','2999-01-01T00:00:00',0,'2026-01-01T00:00:00')");
        breakGlass.run(sentinel("emp=admin01\n"));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM totp_recovery WHERE user_id='a1' AND used=0", Integer.class)).isZero();
    }

    @Test
    void resetsPassword_andRejectsTheOldOne() throws Exception {
        breakGlass.run(sentinel("emp=admin01\npassword=" + NEW_PW + "\n"));

        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"" + OLD_PW + "\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"" + NEW_PW + "\"}"))
            .andExpect(status().isOk());
    }

    /** 비밀번호 재설정은 살아 있는 세션도 끊어야 한다 — 탈취 의심이 복구 사유일 수 있다(salt 교체 경로 재사용). */
    @Test
    void passwordResetInvalidatesAlreadyAuthenticatedSessions() throws Exception {
        MockHttpSession session = login(OLD_PW);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        breakGlass.run(sentinel("emp=admin01\npassword=" + NEW_PW + "\n"));

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    /**
     * 2FA만 풀고 유예를 그대로 두면 만료된 admin은 로그인만 되고 관리 API는 계속 403 — 잠긴 채로 남는다.
     * 그래서 복구 전 403 → 복구 후 200까지 확인한다(관리자 2FA 초기화와 같은 이유의 유예 재시작).
     */
    @Test
    void restartsThe2faGrace_soTheAdminIsNotBlockedRightBackOut() throws Exception {
        jdbc.update("UPDATE app_user SET totp_grace_start='2026-01-01T00:00:00' WHERE id='a1'");
        mvc.perform(get("/api/admin/users").session(login(OLD_PW)))
            .andExpect(status().isForbidden());   // 유예 만료 = 등록 전까지 차단

        breakGlass.run(sentinel("emp=admin01\n"));

        mvc.perform(get("/api/admin/users").session(login(OLD_PW)))
            .andExpect(status().isOk());
    }

    /** 비활성 계정은 비밀번호가 맞아도 로그인 자체가 막힌다 — 다음 시도를 막는 것은 전부 걷어낸다. */
    @Test
    void reactivatesADisabledAccount() throws Exception {
        jdbc.update("UPDATE app_user SET status='disabled' WHERE id='a1'");
        breakGlass.run(sentinel("emp=admin01\n"));

        assertThat(users.findById("a1").status()).isEqualTo("active");
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"" + OLD_PW + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void consumesTheSentinelOnSuccess() {
        Path file = sentinel("emp=admin01\n");
        breakGlass.run(file);
        assertThat(Files.exists(file)).isFalse();                         // 남으면 매 기동마다 재실행 = 상시 백도어
        assertThat(Files.exists(processingOf(file))).isFalse();           // 처리 표식도 정리한다
    }

    @Test
    void writesAnAuditRowNamingTheTarget() {
        breakGlass.run(sentinel("emp=admin01\n"));
        assertThat(auditRows()).singleElement().satisfies(row -> {
            assertThat(row.get("act")).isEqualTo("auth.break_glass");
            assertThat(row.get("target")).isEqualTo("admin01");
        });
    }

    // ---- 출처 검증: 전제가 확인되지 않으면 실행하지 않는다 ----

    /**
     * 이 기능의 보안 논거 그 자체 — WORKNOTE_STORAGE_STRICT=false로 뜬 755 디렉토리에 다른 로컬 사용자가
     * 미리 심어둔 센티넬은 거절돼야 한다. 실행되면 DB를 읽지도 못하는 사람이 관리자 계정을 가져간다.
     */
    @Test
    void refusesAPrePlantedSentinelInAPermissiveDirectory() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        enable2fa();
        Path file = sentinel("emp=admin01\npassword=" + NEW_PW + "\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("700");

        assertThat(Files.exists(file)).isTrue();     // 지우지 않는다 — 우리 것이라고 단정할 수 없다
        assertThat(totp.isEnabled("a1")).isTrue();   // 계정은 손대지 않았다
        assertThat(auditRows()).isEmpty();
    }

    /**
     * POSIX 권한이 없는 파일시스템에서는 소유자·권한을 확인할 방법이 없다 = 이 기능의 전제를 증명할 수 없다.
     * 그래서 <b>기능 자체가 꺼진다</b> — 계정도, 파일도 건드리지 않는다(우리 것이라고 단정할 근거조차 없다).
     *
     * <p>진짜 비-POSIX 파일시스템이 필요하므로 JDK 기본 제공자인 zip 파일시스템을 쓴다(의존성 추가 없음).
     * 가드를 빼면 {@code readAttributes(PosixFileAttributes)}가 {@code UnsupportedOperationException}으로
     * 터지므로 이 테스트는 실제로 그 분기를 붙잡는다.
     */
    @Test
    void nonPosixFileSystemDisablesTheFeatureEntirely() throws IOException {
        enable2fa();
        try (FileSystem zipfs = FileSystems.newFileSystem(dir.resolve("fs.zip"), Map.of("create", "true"))) {
            assumeTrue(!zipfs.supportedFileAttributeViews().contains("posix"), "POSIX를 지원하는 zipfs — skip");
            Path file = zipfs.getPath("/break-glass");
            Files.writeString(file, "emp=admin01\npassword=" + NEW_PW + "\n", StandardCharsets.UTF_8);

            List<ILoggingEvent> events = capturingLogs(() -> breakGlass.run(file));   // 예외 없이 통과해야 한다

            assertThat(Files.exists(file)).isTrue();
            assertThat(totp.isEnabled("a1")).isTrue();
            assertThat(auditRows()).isEmpty();
            assertThat(events).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("POSIX"));
        }
    }

    @Test
    void refusesAGroupReadableSentinel() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        enable2fa();
        Path file = sentinel("emp=admin01\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("600");
        assertThat(totp.isEnabled("a1")).isTrue();
    }

    // ---- 실패는 전부 기동 실패 + 파일 보존 ----

    @Test
    void shortPasswordFailsStartup_andKeepsTheFileAndTheAccountUntouched() {
        enable2fa();
        Path file = sentinel("emp=admin01\npassword=short\n");

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("10");

        assertThat(Files.exists(file)).isTrue();          // 운영자가 고쳐서 재기동할 수 있게 남긴다
        assertThat(totp.isEnabled("a1")).isTrue();        // 검증이 수술보다 앞선다 — 반쯤 적용 금지
        assertThat(auditRows()).isEmpty();
    }

    /** 상한 초과는 "설정은 됐는데 로그인은 안 되는" 비밀번호를 만든다 = 두 번째 브레이크글래스를 강요한다. */
    @Test
    void overlongPasswordFailsStartup_andChangesNothing() {
        Path file = sentinel("emp=admin01\npassword=" + "p".repeat(PasswordPolicy.MAX_LENGTH + 1) + "\n");

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(PasswordPolicy.MAX_LENGTH));

        assertThat(Files.exists(file)).isTrue();
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void unknownEmpFailsStartup_andKeepsTheFile() {
        Path file = sentinel("emp=nosuchuser\n");
        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nosuchuser");
        // 사번 확인은 선점보다 앞이다 — 오타 하나로 .processing이 남아 다음 기동까지 막으면 안 된다.
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(processingOf(file))).isFalse();
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void unparseableContentFailsStartup_andKeepsTheFile() {
        Path file = sentinel("사번은 admin01 입니다\n");   // '=' 없는 줄 = 알 수 없는 키
        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key=value");
        assertThat(Files.exists(file)).isTrue();
    }

    /**
     * 접근 오류를 "없음"으로 처리하면, 복구를 요청한 운영자가 아무 설명 없이 잠긴 채로 남는다.
     * 부모 디렉토리에서 실행(search) 권한을 뺏어 stat 자체를 막는다(root면 권한을 무시하므로 skip).
     */
    @Test
    void inaccessibleSentinelFailsStartup_insteadOfLookingAbsent() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        Path file = sentinel("emp=admin01\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rw-------"));   // x 제거 = 탐색 불가
        assumeTrue(!Files.isReadable(file), "접근을 막을 수 없는 환경(root 등) — skip");

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("존재 여부");
    }

    /**
     * 커밋과 삭제 사이에 죽으면 계정은 바뀌었는데 센티넬은 남는다 — 다음 기동이 조용히 재적용하면
     * 그 사이의 2FA 재등록·비밀번호 변경을 말없이 되돌린다. 적용 여부를 모르므로 재시도 대신 세운다.
     */
    @Test
    void leftoverProcessingFileFailsStartup_ratherThanReplayingSilently() throws IOException {
        enable2fa();
        Path file = dir.resolve("break-glass");
        Path processing = processingOf(file);
        Files.writeString(processing, "emp=admin01\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("중단");

        assertThat(Files.exists(processing)).isTrue();   // 운영자가 확인할 증거를 지우지 않는다
        assertThat(totp.isEnabled("a1")).isTrue();
        assertThat(auditRows()).isEmpty();
    }

    /**
     * 선점(rename)에 실패하면 수술 전에 멈춰야 한다 — 옮기지 못한 채 진행하면 다음 기동이 같은 파일을 다시 적용한다.
     * 부모 디렉토리에서 쓰기 권한을 뺏어 실제 rename 실패를 만든다(root면 권한을 무시하므로 skip).
     *
     * <p><b>{@code ATOMIC_MOVE} 자체에 대한 테스트는 없다 — 인프로세스에서 관측할 수 없기 때문이다.</b>
     * POSIX에서는 일반 {@code Files.move}도 {@code rename(2)} 한 번이라 이미 원자적이고, 두 방식의 차이는
     * "원자적으로 못 옮기는 제공자에서 조용히 copy+delete로 떨어지지 않고 예외로 세운다"는 것뿐이다.
     * 그 상황을 만들려면 원자적 rename을 지원하지 않는 파일시스템 제공자가 필요하고, 그건 이 테스트가
     * 증명하려는 성질(선점 실패 시 수술 전 중단)과 다른 층이다. 통과하는 가짜 단언을 만드느니 비워 둔다.
     */
    @Test
    void claimFailureFailsStartup_beforeAnySurgery() throws IOException {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        enable2fa();
        Path file = sentinel("emp=admin01\n");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        assumeTrue(!Files.isWritable(dir), "쓰기를 막을 수 없는 환경(root 등) — skip");

        assertThatThrownBy(() -> breakGlass.run(file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(file.toString());

        assertThat(totp.isEnabled("a1")).isTrue();
        assertThat(auditRows()).isEmpty();
    }

    /**
     * 커밋 뒤 정리 실패는 기동 실패다. 남은 .processing은 다음 기동을 세우므로(위 테스트), 그 정지가
     * 아무 설명 없이 찾아오지 않도록 지금 이유와 함께 멈춘다.
     * (rename은 되는데 unlink만 실패하는 상태를 밖에서 만들 수 없어 정리 단계를 직접 호출한다)
     */
    @Test
    void failureToCleanUpTheProcessingFileFailsStartup() throws IOException {
        Path stuck = dir.resolve("break-glass.processing");
        Files.createDirectory(stuck);
        Files.writeString(stuck.resolve("blocker"), "x", StandardCharsets.UTF_8);   // 비어 있지 않으면 삭제 불가

        assertThatThrownBy(() -> breakGlass.delete(stuck))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("직접 지운 뒤 재기동");
    }

    // ---- 시끄럽되, 비밀번호는 절대 남기지 않는다 ----

    @Test
    void logsAWarnNamingTheUser_butNeverThePassword() {
        List<ILoggingEvent> events = capturingLogs(() ->
            breakGlass.run(sentinel("emp=admin01\npassword=" + NEW_PW + "\n")));

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getLevel().toString()).isEqualTo("WARN");
            assertThat(e.getFormattedMessage()).contains("admin01");
        });
        assertNoSecret(events, NEW_PW);
    }

    /**
     * 실패 경로가 진짜 위험한 쪽이다 — 던진 예외는 기동 스택트레이스로 콘솔·로그에 그대로 찍힌다.
     * 특히 '=' 없는 줄은 Properties가 <b>키 이름</b>으로 만들기 때문에, 비밀번호만 적힌 파일이 통째로 새기 쉽다.
     */
    @Test
    void failurePathsNeverLeakThePassword_notInMessagesNotInStackTraces() {
        String secret = "leak-check-place-holder";
        List<String> contents = List.of(
            secret + "\n",                                   // '=' 없는 줄 = 키 이름이 곧 비밀번호
            "emp=admin01\npasword=" + secret + "\n",         // 오타 키
            "emp=admin01\npassword=" + secret.substring(0, 5) + "\n",   // 정책 미달
            "emp=nosuchuser\npassword=" + secret + "\n");    // 사번 없음(수술 단계 실패)

        for (String content : contents) {
            Path file = sentinel(content);
            var captured = new Object() { Throwable thrown; };
            List<ILoggingEvent> events = capturingLogs(() ->
                captured.thrown = catchThrowable(() -> breakGlass.run(file)));

            assertThat(captured.thrown).as("내용: %s", content.replace(secret, "<secret>"))
                .isInstanceOf(IllegalStateException.class);
            assertThat(stackTraceOf(captured.thrown)).doesNotContain(secret);
            assertNoSecret(events, secret);
            cleanUp(file);
        }
    }

    // ---- helpers ----

    private boolean posix() {
        return dir.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** 운영자가 규정대로 만든 센티넬 — 600, 부모 700. 출처 검증을 통과하는 유일한 형태다. */
    private Path sentinel(String content) {
        try {
            Path file = dir.resolve("break-glass");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path processingOf(Path sentinel) {
        return sentinel.resolveSibling(sentinel.getFileName() + BreakGlassRecovery.PROCESSING_SUFFIX);
    }

    private void cleanUp(Path sentinel) {
        try {
            Files.deleteIfExists(sentinel);
            Files.deleteIfExists(processingOf(sentinel));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void enable2fa() {
        totp.setup("a1", "admin01");
        totp.confirm("a1", Totp.codeAt(totp.currentSecretForTest("a1"), Instant.now().getEpochSecond()));
    }

    private MockHttpSession login(String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"admin01\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
        return session;
    }

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList("SELECT who, act, target FROM audit_log WHERE act='auth.break_glass'");
    }

    /** 루트 로거에 붙여 실행 구간의 모든 로그를 수집 — 어느 로거로 새든 잡는다. */
    private static List<ILoggingEvent> capturingLogs(Runnable action) {
        ch.qos.logback.classic.Logger root =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            action.run();
        } finally {
            root.detachAppender(appender);
        }
        return appender.list;
    }

    private static void assertNoSecret(List<ILoggingEvent> events, String secret) {
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain(secret);
            assertThat(throwableTextOf(e)).doesNotContain(secret);
        });
    }

    /** 로그에 실린 예외를 원인 체인·스택프레임까지 펼친 문자열 — toString만 보면 메시지도 못 본다. */
    private static String throwableTextOf(ILoggingEvent event) {
        StringBuilder out = new StringBuilder();
        for (IThrowableProxy p = event.getThrowableProxy(); p != null; p = p.getCause()) {
            out.append(p.getClassName()).append(' ').append(p.getMessage()).append('\n');
            for (StackTraceElementProxy frame : p.getStackTraceElementProxyArray()) {
                out.append(frame.getSTEAsString()).append('\n');
            }
        }
        return out.toString();
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
