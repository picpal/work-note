package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M-6 P2 — 신규 설치를 그대로 재현한다: DB 파일이 <b>없는</b> 상태에서 앱이 뜨고, Flyway/SQLite가 파일을 만들고,
 * 후반 패스가 그 파일을 600으로 맞춘다. 다른 테스트가 전부 인메모리 DB라 여기만이 실제 파일 경로를 지난다.
 *
 * <p>단위 테스트는 후반 패스의 <i>동작</i>만 보증한다. BFPP 빈이 {@code ApplicationListener}로도 등록되느냐는
 * 컨테이너 배선 문제라 여기서만 증명된다 — 배선이 빠지면 아무 에러 없이 신규 설치 DB만 644로 남는다.
 */
@SpringBootTest
class StoragePermissionGuardStartupTest {

    /** 컨텍스트 로드(= @DynamicPropertySource) 전에 확정돼야 해서 static 초기화로 만든다. */
    private static final Path BASE = createBase();

    private static final Path DB = BASE.resolve("data/worknote.db");

    private static Path createBase() {
        try {
            return Files.createTempDirectory("worknote-storage-guard");
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

    /** 기본값 fail-closed는 application.yml에도 살아 있어야 한다 — 운영자 가이드가 약속하는 키 이름까지 못박는다. */
    @Test
    void strictIsWiredAndDefaultsToTrue() {
        assertThat(ctx.getEnvironment().getProperty("worknote.storage.strict", Boolean.class)).isTrue();
    }

    @Test
    void dbFileCreatedDuringStartupEndsUp600() throws IOException {
        assumeTrue(StoragePermissions.posixSupported(BASE), "POSIX 미지원 — skip");
        assertThat(Files.isRegularFile(DB)).isTrue();   // 앱이 만든 실제 파일이다(테스트가 미리 만들지 않았다)
        assertThat(Files.getPosixFilePermissions(DB))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(DB.getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * 위 단언은 프로세스 umask가 077이면 후반 패스 없이도 통과할 수 있다(개발 머신은 보통 022).
     * 배선 자체를 umask와 무관하게 못박아 둔다 — 이게 빠지는 게 실제 회귀 시나리오다.
     */
    @Test
    void guardIsRegisteredAsStartupListenerDespiteBeingABeanFactoryPostProcessor() {
        assertThat(ctx.getApplicationListeners()).anyMatch(StoragePermissionGuard.class::isInstance);
    }
}
