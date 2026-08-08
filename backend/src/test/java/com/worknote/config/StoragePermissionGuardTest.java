package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** 기동 게이트 — server 모드는 보정 실패 시 기동을 세우고, local 모드는 WARN 후 계속. */
class StoragePermissionGuardTest {

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
