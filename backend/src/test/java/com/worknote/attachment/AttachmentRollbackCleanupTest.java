package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.setting.SettingService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파일은 트랜잭션 안에서 쓰이고 DB 행은 그 뒤에 들어간다. 트랜잭션이 롤백되면 행은 사라지지만
 * 파일은 남고, purge(deleteForNodes)는 DB 행 기준이라 그 파일을 <b>영영</b> 회수하지 못한다.
 *
 * <p>IOException 경로 정리(AttachmentOrphanCleanupTest)만으로는 절반짜리다 — 여기는 쓰기가 성공한 뒤의 경로다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:attrollback?mode=memory&cache=shared",
    "worknote.upload.dir=build/test-attachment-rollback"
})
class AttachmentRollbackCleanupTest {
    @Autowired AttachmentService svc;
    @Autowired AttachmentMapper mapper;
    @Autowired SettingService settings;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void seedNode() {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id = 'n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('n1',NULL,'note','노트',1)");
    }

    /** 호출부가 롤백하는 경우 — store()는 성공했지만 바깥 트랜잭션이 뒤집힌다. */
    @Test
    void callerRollbackRemovesWrittenFile() {
        Path[] written = new Path[1];

        assertThatThrownBy(() -> tx.execute(status -> {
            AttachmentRow row = svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local");
            written[0] = svc.pathOf(row);
            assertThat(Files.exists(written[0])).isTrue();   // 커밋 전에는 존재한다
            throw new IllegalStateException("의도적 롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(written[0]).isNotNull();
        assertThat(Files.exists(written[0])).isFalse();
        assertThat(svc.findByNode("n1")).isEmpty();
    }

    /**
     * insert가 터지는 경우 — 파일은 이미 디스크에 있고 행만 실패한다.
     * 경로를 미리 알 수 없으므로 <b>전용 임시 루트</b>를 주고 남은 파일이 0개인지로 확인한다
     * (공용 업로드 루트에서 세면 다른 테스트의 파일과 섞여 순서 의존이 된다).
     */
    @Test
    void insertFailureRemovesWrittenFile(@TempDir Path root) {
        AttachmentMapper failingInsert = (AttachmentMapper) Proxy.newProxyInstance(
            AttachmentMapper.class.getClassLoader(),
            new Class<?>[]{AttachmentMapper.class},
            (proxy, method, args) -> {
                if ("insert".equals(method.getName())) {
                    throw new DataIntegrityViolationException("제약 위반");
                }
                try {
                    return method.invoke(mapper, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        AttachmentService failing =
            new AttachmentService(failingInsert, settings, clock, root.toString());

        assertThatThrownBy(() ->
            tx.execute(status -> failing.store("n1", "a.png", new byte[]{1, 2, 3}, "local")))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(regularFileCount(root)).isZero();
    }

    /** 커밋된 트랜잭션은 절대 파일을 지우면 안 된다. */
    @Test
    void committedStoreKeepsFile() {
        Path written = tx.execute(status -> {
            AttachmentRow row = svc.store("n1", "keep.png", new byte[]{7, 8, 9}, "local");
            return svc.pathOf(row);
        });

        assertThat(written).isNotNull();
        assertThat(Files.exists(written)).isTrue();
        assertThat(svc.findByNode("n1")).hasSize(1);
    }

    /** 트랜잭션 밖 호출에서도 터지지 않고 정상 저장된다(동기화 미활성 가드). */
    @Test
    void storeOutsideTransactionStillWorks(@TempDir Path root) {
        AttachmentService bare = new AttachmentService(mapper, settings, clock, root.toString());

        AttachmentRow[] row = new AttachmentRow[1];
        assertThatCode(() -> row[0] = bare.store("n1", "bare.png", new byte[]{4, 5}, "local"))
            .doesNotThrowAnyException();
        assertThat(Files.exists(bare.pathOf(row[0]))).isTrue();
        assertThat(regularFileCount(root)).isEqualTo(1);
    }

    private static long regularFileCount(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
