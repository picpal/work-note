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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
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
        MockEnvironment env = new MockEnvironment();
        env.setProperty("worknote.mode", mode);
        env.setProperty("worknote.upload.dir", uploadDir);
        if (dbUrl != null) {
            env.setProperty("spring.datasource.url", dbUrl);
        }
        StoragePermissionGuard g = new StoragePermissionGuard();
        g.setEnvironment(env);
        return g;
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
}
