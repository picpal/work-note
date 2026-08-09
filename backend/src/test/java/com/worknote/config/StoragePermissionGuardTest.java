package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** 기동 게이트 — server 모드는 보정 실패 시 기동을 세우고, local 모드는 WARN 후 계속. */
class StoragePermissionGuardTest {

    /** 로그를 "안 남겼다"까지 검증해야 해서 appender를 붙인다 — WARN 무감각화가 이 변경의 핵심 이유다. */
    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(StoragePermissionGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private StoragePermissionGuard guard(String mode, String uploadDir, String dbUrl) {
        return guard(mode, uploadDir, dbUrl, null);
    }

    /** @param strict null이면 미설정 — 기본값(fail-closed)이 실제로 true인지까지 이 경로로 검증한다. */
    private StoragePermissionGuard guard(String mode, String uploadDir, String dbUrl, Boolean strict) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("worknote.mode", mode);
        env.setProperty("worknote.upload.dir", uploadDir);
        if (dbUrl != null) {
            env.setProperty("spring.datasource.url", dbUrl);
        }
        if (strict != null) {
            env.setProperty("worknote.storage.strict", strict.toString());
        }
        StoragePermissionGuard g = new StoragePermissionGuard();
        g.setEnvironment(env);
        return g;
    }

    /** 후반 패스 트리거. 이벤트 내용은 쓰지 않지만 실제 타입으로 호출해 시그니처까지 묶어둔다. */
    private ApplicationStartedEvent startedEvent() {
        return new ApplicationStartedEvent(
            new SpringApplication(), new String[0], new GenericApplicationContext(), Duration.ZERO);
    }

    @Test
    void serverMode_failsStartupWhenPermissionsCannotBeApplied(@TempDir Path tmp) throws IOException {
        Path blocked = Files.createFile(tmp.resolve("attachments"));   // 디렉토리 자리를 파일이 막음
        assertThatThrownBy(() -> guard("server", blocked.toString(), "jdbc:sqlite::memory:")
                .postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(blocked.toString());
    }

    @Test
    void localMode_warnsButStarts(@TempDir Path tmp) throws IOException {
        Path blocked = Files.createFile(tmp.resolve("attachments"));
        assertThatCode(() -> guard("local", blocked.toString(), "jdbc:sqlite::memory:")
            .postProcessBeanFactory(null)).doesNotThrowAnyException();
    }

    @Test
    void createsUploadRootAt700(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path uploads = tmp.resolve("data/attachments");
        guard("server", uploads.toString(), "jdbc:sqlite::memory:").postProcessBeanFactory(null);
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void hardensRealDbFileInsidePrivateParent(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path db = Files.createFile(dir.resolve("worknote.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-r--r--"));

        guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:" + db).postProcessBeanFactory(null);

        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** P1 — 이미 있는 넓은 디렉토리는 앱이 조이지 않는다. 대신 경로와 실행할 명령을 알려주고 기동을 세운다. */
    @Test
    void serverMode_failsStartupInsteadOfChmoddingExistingPermissiveDbParent(@TempDir Path tmp)
        throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path db = dir.resolve("worknote.db");

        assertThatThrownBy(() -> guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:" + db)
                .postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(dir.toString())
            .hasMessageContaining("chmod 700 " + dir);

        assertThat(Files.getPosixFilePermissions(dir))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** P1 — server 모드에서 WORKNOTE_DB 미지정(기본 상대 경로 `./worknote.db`)은 기동 실패. */
    @Test
    void serverMode_failsStartupOnDefaultRelativeDbPath(@TempDir Path tmp) {
        assertThatThrownBy(() -> guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:./worknote.db")
                .postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WORKNOTE_DB");
    }

    /** P1 — 업로드 루트도 같은 규칙. 이미 있는 넓은 디렉토리는 조이지 않고 기동을 세운다. */
    @Test
    void serverMode_failsStartupInsteadOfChmoddingExistingPermissiveUploadRoot(@TempDir Path tmp)
        throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path uploads = Files.createDirectory(tmp.resolve("shared-uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> guard("server", uploads.toString(), "jdbc:sqlite::memory:")
                .postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(uploads.toString())
            .hasMessageContaining("chmod 700 " + uploads);

        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * local 모드는 같은 상황에서 <b>경고조차 하지 않고</b> 조용히 뜬다. 개인 PC에서 755 폴더는 정상이라
     * 매 기동 경고가 찍히면 정작 봐야 할 경고까지 무시하게 된다. 물론 디렉토리도 건드리지 않는다.
     */
    @Test
    void localMode_doesNotWarnOrChmodPreExistingPermissiveDirectories(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path cwd = Files.createDirectory(tmp.resolve("cwd"));
        Files.setPosixFilePermissions(cwd, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path uploads = Files.createDirectory(tmp.resolve("shared-uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path db = cwd.resolve("worknote.db");

        List<ILoggingEvent> logs = captureLogs(() ->
            guard("local", uploads.toString(), "jdbc:sqlite:" + db).postProcessBeanFactory(null));

        assertThat(logs).noneMatch(e -> e.getLevel() == Level.WARN);
        assertThat(Files.getPosixFilePermissions(cwd))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** 침묵시키는 건 "기존 디렉토리가 넓다"뿐 — 진짜 오작동은 local 모드에서도 WARN으로 남는다. */
    @Test
    void localMode_stillWarnsOnRealMalfunction(@TempDir Path tmp) throws IOException {
        Path blocked = Files.createFile(tmp.resolve("attachments"));   // 업로드 루트를 만들 수 없음

        List<ILoggingEvent> logs = captureLogs(() ->
            guard("local", blocked.toString(), "jdbc:sqlite::memory:").postProcessBeanFactory(null));

        assertThat(logs).anyMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains(blocked.toString()));
    }

    /** local 모드는 개인 PC 무설정 기본값 — 상대 경로 DB로도 뜨고, 작업 디렉토리를 건드리지 않는다. */
    @Test
    void localMode_startsZeroConfigAndLeavesWorkingDirectoryAlone(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path cwd = Paths.get("").toAbsolutePath();
        var before = Files.getPosixFilePermissions(cwd);

        assertThatCode(() -> guard("local", tmp.resolve("att").toString(), "jdbc:sqlite:./worknote.db")
            .postProcessBeanFactory(null)).doesNotThrowAnyException();

        assertThat(Files.getPosixFilePermissions(cwd)).isEqualTo(before);
    }

    // --- P2: 신규 설치의 DB 파일은 전반 패스 시점에 아직 없다 (후반 패스) ---

    /**
     * 핵심 회귀 — 전반 패스만 있으면 <b>새로 설치한 배치의 DB가 600이 아니다</b>.
     * BFPP는 DataSource/Flyway보다 먼저 도니 그때는 조일 파일이 없고, 파일을 만드는 건 그 뒤의 SQLite다
     * (프로세스 umask대로 보통 644). 부모 700이 막아주긴 해도 운영자 가이드가 약속한 불변식은 깨진다.
     */
    @Test
    void latePass_hardensDbFileThatDidNotExistDuringTheEarlyPass(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path db = tmp.resolve("data/worknote.db");          // 부모도 아직 없는 신규 설치
        StoragePermissionGuard g = guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:" + db);

        g.postProcessBeanFactory(null);
        assertThat(Files.exists(db)).isFalse();             // 전반 패스가 조일 수 있는 파일이 애초에 없다

        Files.createFile(db);                               // SQLite/Flyway가 만드는 순간을 재현
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-r--r--"));

        g.onApplicationEvent(startedEvent());

        assertThat(Files.getPosixFilePermissions(db))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(db.getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /** 후반 패스도 같은 게이트 — 여기서 처음 발견된 문제라고 통과시키면 발견 시점이 결론을 바꾸는 꼴이 된다. */
    @Test
    void latePass_serverModeFailsStartupOnTheSameKindOfProblem(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path db = Files.createSymbolicLink(dir.resolve("worknote.db"), Files.createFile(tmp.resolve("victim")));

        assertThatThrownBy(() -> guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:" + db)
                .onApplicationEvent(startedEvent()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("심볼릭 링크");
    }

    /** 후반 패스의 탈출구도 전반과 같다 — strict를 끄면 WARN. */
    @Test
    void latePass_nonStrictServerModeWarnsInsteadOfFailing(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Path db = Files.createSymbolicLink(dir.resolve("worknote.db"), Files.createFile(tmp.resolve("victim")));

        List<ILoggingEvent> logs = captureLogs(() ->
            guard("server", tmp.resolve("att").toString(), "jdbc:sqlite:" + db, false)
                .onApplicationEvent(startedEvent()));

        assertThat(logs).anyMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("심볼릭 링크"));
    }

    // --- 탈출구: WORKNOTE_STORAGE_STRICT ---

    /**
     * 게이트의 전체 판정표. 축은 셋 — 모드 × strict × 저장소 상태.
     * <ul>
     *   <li>CLEAN: 없는 디렉토리(우리가 700으로 만든다) — 조용히 통과해야 한다</li>
     *   <li>PERMISSIVE: 이미 있는 755 디렉토리 — 앱이 조이지 않는 "배치 상태" 지적</li>
     *   <li>MALFUNCTION: 디렉토리 자리를 파일이 막음 — 진짜 오작동</li>
     * </ul>
     * local 모드는 strict와 무관하게 기존 동작 그대로다(755 개인 폴더는 정상이라 지적조차 하지 않는다).
     */
    @ParameterizedTest(name = "{0} · strict={1} · {2} → {3}")
    @CsvSource({
        "server, true,  CLEAN,       SILENT",
        "server, false, CLEAN,       SILENT",
        "server, true,  PERMISSIVE,  THROW",
        "server, false, PERMISSIVE,  WARN",
        "server, true,  MALFUNCTION, THROW",
        "server, false, MALFUNCTION, WARN",
        "local,  true,  CLEAN,       SILENT",
        "local,  false, CLEAN,       SILENT",
        "local,  true,  PERMISSIVE,  SILENT",
        "local,  false, PERMISSIVE,  SILENT",
        "local,  true,  MALFUNCTION, WARN",
        "local,  false, MALFUNCTION, WARN",
    })
    void strictSwitchDecidesFailVsWarn(String mode, boolean strict, String state, String outcome,
        @TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path uploads = tmp.resolve("attachments");
        switch (state) {
            case "CLEAN" -> { /* 만들지 않는다 — 없는 디렉토리는 게이트가 700으로 생성한다 */ }
            case "PERMISSIVE" -> Files.setPosixFilePermissions(
                Files.createDirectory(uploads), PosixFilePermissions.fromString("rwxr-xr-x"));
            case "MALFUNCTION" -> Files.createFile(uploads);
            default -> throw new IllegalArgumentException(state);
        }
        StoragePermissionGuard g = guard(mode, uploads.toString(), "jdbc:sqlite::memory:", strict);

        if ("THROW".equals(outcome)) {
            assertThatThrownBy(() -> g.postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(uploads.toString());
            return;
        }
        List<ILoggingEvent> logs = captureLogs(() -> g.postProcessBeanFactory(null));
        if ("WARN".equals(outcome)) {
            assertThat(logs).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains(uploads.toString()));
        } else {
            assertThat(logs).noneMatch(e -> e.getLevel() == Level.WARN);
        }
    }

    /** 기본값은 fail-closed — 아무것도 설정하지 않은 server 모드는 여전히 기동을 세운다. */
    @Test
    void strictDefaultsToTrueWhenUnset(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path uploads = Files.createDirectory(tmp.resolve("shared-uploads"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> guard("server", uploads.toString(), "jdbc:sqlite::memory:", null)
                .postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 끄고 뜬 기동의 WARN은 <b>통과 로그로 오해될 수 없어야</b> 한다 —
     * 문제 목록과 "엄격 모드를 명시적으로 껐다"가 한 줄에 같이 있어야 한다.
     */
    @Test
    void nonStrictWarnNamesBothTheProblemAndTheDisabledSwitch(@TempDir Path tmp) throws IOException {
        assumeTrue(StoragePermissions.posixSupported(tmp), "POSIX 미지원 — skip");
        Path uploads = Files.createDirectory(tmp.resolve("backup-readable"));
        Files.setPosixFilePermissions(uploads, PosixFilePermissions.fromString("rwxr-x---"));

        List<ILoggingEvent> logs = captureLogs(() ->
            guard("server", uploads.toString(), "jdbc:sqlite::memory:", false).postProcessBeanFactory(null));

        assertThat(logs).anyMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains(uploads.toString())
            && e.getFormattedMessage().contains("chmod 700 " + uploads)
            && e.getFormattedMessage().contains("WORKNOTE_STORAGE_STRICT")
            && e.getFormattedMessage().contains("점검을 통과한 것이 아닙니다"));
        assertThat(Files.getPosixFilePermissions(uploads))
            .isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));   // 끈다고 앱이 대신 조이지도 않는다
    }
}
