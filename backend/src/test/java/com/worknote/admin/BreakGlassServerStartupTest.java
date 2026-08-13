package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.worknote.WorknoteApplication;
import com.worknote.auth.PasswordHasher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * server 모드 기동 경로 e2e — 이 기능의 존재 이유(잠긴 관리자 복구)와 그 안전장치(전제 미확인 시 기동 중단)를
 * <b>실제 기동</b>으로 확인한다. 다른 테스트는 {@code run(Path)}를 직접 부르므로, "기동 중에도 그렇게 되는가"는
 * 여기서만 증명된다.
 *
 * <p>웹 서버는 띄우지 않는다({@link WebApplicationType#NONE}) — 필요한 건 컨텍스트 수명주기와
 * {@code ApplicationStartedEvent}뿐이고, 기동 실패가 <b>컨텍스트를 세운다</b>는 사실은 그 이벤트에서 던진
 * 예외로만 확인할 수 있다(@SpringBootTest는 실패한 컨텍스트를 캐시할 뿐 "실패했음"을 단언할 수단을 주지 않는다).
 *
 * <p>DB는 임시 디렉토리의 실제 파일이다. 브레이크글래스는 {@code ApplicationRunner}보다 먼저 돌기 때문에
 * ({@code AdminBootstrap}이 그 뒤다) 계정은 기동 <b>전에</b> 심어둬야 한다 — Flyway를 먼저 한 번 돌려 스키마를
 * 만들고 사용자를 직접 넣는다. 앱은 그 다음에 뜬다.
 */
class BreakGlassServerStartupTest {

    private static final String OLD_PW = "old-place-holder";
    private static final String NEW_PW = "new-place-holder";

    private Path base;
    private Path db;
    private Path sentinel;

    @BeforeEach
    void prepareDeployment() throws IOException {
        base = Files.createTempDirectory("worknote-break-glass-server");
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        db = base.resolve("worknote.db");
        sentinel = base.resolve(BreakGlassFile.FILE_NAME);
        migrate();
        seedLockedOutAdmin();
    }

    @AfterEach
    void cleanup() throws IOException {
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwx------"));
        try (var paths = Files.walk(base)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** 휴대폰을 잃은 유일한 관리자 — 2FA 등록됨, 유예 만료. 이 상태에서 파일 하나로 돌아올 수 있어야 한다. */
    @Test
    void recoversTheLockedOutAdminDuringStartup() throws Exception {
        writeSentinel("emp=admin01\npassword=" + NEW_PW + "\n");

        try (ConfigurableApplicationContext ctx = boot()) {
            assertThat(ctx.isRunning()).isTrue();
        }

        assertThat(rows("SELECT user_id FROM user_totp")).isEmpty();          // 2FA 해제
        assertThat(rows("SELECT totp_grace_start FROM app_user WHERE id='a1'"))
            .singleElement().isNotEqualTo("2026-01-01T00:00:00");             // 유예 재시작
        assertThat(rows("SELECT act FROM audit_log WHERE act='auth.break_glass'")).hasSize(1);
        assertThat(passwordMatches(NEW_PW)).isTrue();
        assertThat(passwordMatches(OLD_PW)).isFalse();
        assertThat(Files.exists(sentinel)).isFalse();                         // 1회용 — 소비됐다
        assertThat(Files.exists(processing())).isFalse();
    }

    /**
     * 전제가 확인되지 않으면 <b>기동을 세운다</b>. 755 디렉토리(=WORKNOTE_STORAGE_STRICT=false로 허용되는 배치)에
     * 다른 사용자가 미리 심어둘 수 있었던 파일은 실행하지 않는다 — 그대로 실행하면 DB를 읽지도 못하는 사람이
     * 관리자 계정을 가져간다. 이 정지는 storage.strict와 무관해야 한다(그 스위치는 하드닝 경고에 대한 선택이다).
     */
    @Test
    void refusesToStartWhenTheSentinelProvenanceCannotBeTrusted() throws Exception {
        assumeTrue(posix(), "POSIX 미지원 — skip");
        writeSentinel("emp=admin01\npassword=" + NEW_PW + "\n");
        Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("rwxr-xr-x"));

        Throwable thrown = catchThrowable(() -> {
            try (ConfigurableApplicationContext ctx = boot("worknote.storage.strict=false")) {
                // 여기 도달하면 실패다 — 컨텍스트가 떴다는 뜻이므로 아래 단언이 잡는다
            }
        });

        assertThat(causeChain(thrown)).anyMatch(m -> m.contains("브레이크글래스") && m.contains("700"));
        assertThat(Files.exists(sentinel)).isTrue();                          // 우리 것이 아니므로 지우지 않는다
        assertThat(rows("SELECT user_id FROM user_totp")).hasSize(1);         // 계정은 그대로
        assertThat(rows("SELECT act FROM audit_log WHERE act='auth.break_glass'")).isEmpty();
        assertThat(passwordMatches(OLD_PW)).isTrue();
    }

    /** 중단 흔적이 남아 있으면 적용 여부를 알 수 없다 — 조용한 재적용 대신 기동을 세우고 운영자에게 넘긴다. */
    @Test
    void refusesToStartWhenAPreviousAttemptWasInterrupted() throws Exception {
        Files.writeString(processing(), "emp=admin01\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(processing(), PosixFilePermissions.fromString("rw-------"));

        Throwable thrown = catchThrowable(() -> {
            try (ConfigurableApplicationContext ctx = boot()) {
                // 도달하면 실패
            }
        });

        assertThat(causeChain(thrown)).anyMatch(m -> m.contains("중단"));
        assertThat(Files.exists(processing())).isTrue();
        assertThat(rows("SELECT act FROM audit_log WHERE act='auth.break_glass'")).isEmpty();
    }

    // ---- 배치 준비 ----

    private void migrate() {
        Flyway.configure().dataSource(url(), null, null)
            .locations("classpath:db/migration/sqlite").load().migrate();
    }

    private void seedLockedOutAdmin() {
        String salt = PasswordHasher.newSalt();
        execute("INSERT INTO app_user (id, emp, name, role_id, status, totp_grace_start) "
                + "VALUES ('a1','admin01','관리','admin','active','2026-01-01T00:00:00')",
            "INSERT INTO user_credential (user_id, salt, password_hash) VALUES ('a1','"
                + salt + "','" + PasswordHasher.hash(OLD_PW, salt) + "')",
            "INSERT INTO user_totp (user_id, secret_enc, enabled, last_step, created_at) "
                + "VALUES ('a1','enc',1,0,'2026-01-01T00:00:00')");
    }

    private void writeSentinel(String content) throws IOException {
        Files.writeString(sentinel, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(sentinel, PosixFilePermissions.fromString("rw-------"));
    }

    private Path processing() {
        return base.resolve(BreakGlassFile.FILE_NAME + BreakGlassRecovery.PROCESSING_SUFFIX);
    }

    /** 웹 서버 없이 컨텍스트만 — 포트를 열지 않는다. */
    private ConfigurableApplicationContext boot(String... extraProperties) {
        List<String> props = new ArrayList<>(List.of(
            "--spring.datasource.url=" + url(),
            "--worknote.mode=server",
            "--worknote.admin-password=seed-admin-pw",
            "--worknote.upload.dir=" + base.resolve("attachments")));
        for (String extra : extraProperties) {
            props.add("--" + extra);
        }
        return new SpringApplicationBuilder(WorknoteApplication.class)
            .web(WebApplicationType.NONE)
            .run(props.toArray(String[]::new));
    }

    // ---- 관측 ----

    private boolean posix() {
        return base.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private String url() {
        return "jdbc:sqlite:" + db;
    }

    private void execute(String... sql) {
        try (Connection conn = DriverManager.getConnection(url()); Statement st = conn.createStatement()) {
            for (String one : sql) {
                st.executeUpdate(one);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> rows(String sql) {
        try (Connection conn = DriverManager.getConnection(url()); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean passwordMatches(String password) {
        List<String> salt = rows("SELECT salt FROM user_credential WHERE user_id='a1'");
        List<String> hash = rows("SELECT password_hash FROM user_credential WHERE user_id='a1'");
        return PasswordHasher.verify(password, salt.get(0), hash.get(0));
    }

    /** 기동 실패는 원인 체인 어딘가에 실린다 — 어느 층에서 감싸든 메시지로 찾는다. */
    private static List<String> causeChain(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return messages;
    }
}
