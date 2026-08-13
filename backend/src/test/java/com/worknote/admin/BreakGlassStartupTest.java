package com.worknote.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 기동 경로 자체를 증명한다 — 실제 파일 DB 옆에 센티넬을 두고 앱을 띄운다.
 * 다른 테스트는 전부 인메모리라 센티넬 경로가 아예 유도되지 않으므로({@link BreakGlassFileTest}) 여기서만 지난다.
 *
 * <p>모드는 local(무인증). 이 모드엔 계정 개념이 없으니 <b>계정 수술은 하지 않고 파일만 치운다</b>.
 * 그 "하지 않았음"이 여기선 강하게 증명된다: 파일이 없는 사번을 가리키므로, 만약 실행됐다면
 * 사번 없음으로 기동이 실패해 이 컨텍스트가 아예 뜨지 못한다.
 */
@SpringBootTest
class BreakGlassStartupTest {

    /** 컨텍스트 로드(= @DynamicPropertySource) 전에 확정돼야 해서 static 초기화로 만든다. */
    private static final Path BASE = createBase();

    private static final Path DB = BASE.resolve("data/worknote.db");
    private static final Path SENTINEL = BASE.resolve("data/break-glass");

    private static Path createBase() {
        try {
            Path base = Files.createTempDirectory("worknote-break-glass");
            Files.createDirectories(base.resolve("data"));
            // 기동 전에 이미 놓여 있어야 한다 — 운영자가 파일을 두고 재기동하는 상황 그대로.
            Files.writeString(base.resolve("data/break-glass"),
                "emp=nosuchuser\n", StandardCharsets.UTF_8);
            return base;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("worknote.upload.dir", () -> BASE.resolve("data/attachments").toString());
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var paths = Files.walk(BASE)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired AbstractApplicationContext ctx;
    @Autowired BreakGlassRecovery breakGlass;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path tmp;

    /** 경로 유도(DB 부모) + 소비까지 한 번에 — 설정 없이 DB 옆의 파일을 찾아 치웠다는 뜻이다. */
    @Test
    void sentinelNextToTheDbIsConsumedDuringStartup() {
        assertThat(Files.exists(SENTINEL)).isFalse();
    }

    /**
     * 배선이 빠지면 아무 에러 없이 "센티넬이 영원히 남는" 상태가 된다 —
     * 즉 매 기동마다 되살아나는 백도어. 조용한 회귀라 여기서만 잡힌다.
     */
    @Test
    void recoveryIsRegisteredAsAStartupListener() {
        assertThat(ctx.getApplicationListeners()).anyMatch(BreakGlassRecovery.class::isInstance);
    }

    /**
     * 경로는 설정으로 열지 않는다 — 임의 경로(공용·전체쓰기 디렉토리)를 가리키는 오설정 한 번으로
     * 이 기능이 뒷문이 아니라는 근거가 통째로 깨진다. 설정 키가 되살아나는 회귀를 여기서 막는다.
     */
    @Test
    void thereIsNoPathOverrideProperty() {
        assertThat(ctx.getEnvironment().getProperty("worknote.break-glass.file")).isNull();
    }

    /** local 모드는 <b>치우기만</b> 한다 — 계정도 감사도 건드리지 않는다(수술의 근거가 없는 모드다). */
    @Test
    void localModeRemovesTheSentinelWithoutTouchingAnyAccount() throws IOException {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("INSERT INTO app_user (id, emp, name, role_id, status) VALUES ('bg1','local01','로컬','admin','disabled')");
        jdbc.update("INSERT INTO user_totp (user_id, secret_enc, enabled, last_step, created_at) "
            + "VALUES ('bg1','enc',1,0,'2026-01-01T00:00:00')");
        Path file = tmp.resolve("break-glass");
        Files.writeString(file, "emp=local01\npassword=place-holder-pw\n", StandardCharsets.UTF_8);

        List<ILoggingEvent> events = capturingLogs(() -> breakGlass.run(file));

        assertThat(Files.exists(file)).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM user_totp WHERE user_id='bg1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id='bg1'", String.class)).isEqualTo("disabled");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        assertThat(warnings(events)).anyMatch(m -> m.contains("local 모드") && m.contains(file.toString()));
        // 무시하고 지우는 파일이라도 그 안의 비밀번호는 로그로 새지 않는다.
        assertThat(events).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("place-holder-pw"));
    }

    /** 중단 흔적(.processing)도 local 모드에선 정지 사유가 아니다 — 되살릴 로그인이 없는 모드다. */
    @Test
    void localModeAlsoRemovesALeftoverProcessingFile() throws IOException {
        Path processing = tmp.resolve("break-glass" + BreakGlassRecovery.PROCESSING_SUFFIX);
        Files.writeString(processing, "emp=local01\n", StandardCharsets.UTF_8);

        breakGlass.run(tmp.resolve("break-glass"));   // 센티넬 자체는 없다

        assertThat(Files.exists(processing)).isFalse();
    }

    /** 삭제 실패는 local 모드에선 WARN까지 — 아무 수술도 하지 않았으니 재무장할 뒷문이 없다(server 모드는 기동 실패). */
    @Test
    void localModeOnlyWarnsWhenTheSentinelCannotBeDeleted() throws IOException {
        Path file = tmp.resolve("break-glass");
        Files.writeString(file, "emp=local01\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeTrue(!Files.isWritable(tmp), "삭제를 막을 수 없는 환경(root 등) — skip");

            List<ILoggingEvent> events = capturingLogs(() -> breakGlass.run(file));   // 예외 없이 통과해야 한다

            assertThat(Files.exists(file)).isTrue();
            assertThat(warnings(events)).anyMatch(m -> m.contains("삭제하지 못했") && m.contains(file.toString()));
        } finally {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static List<String> warnings(List<ILoggingEvent> events) {
        return events.stream()
            .filter(e -> "WARN".equals(e.getLevel().toString()))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

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
}
