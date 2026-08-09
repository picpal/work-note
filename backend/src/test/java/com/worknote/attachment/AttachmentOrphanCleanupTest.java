package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.setting.SettingService;
import com.worknote.vault.VaultException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 쓰기 실패로 남은 부분 파일은 DB 행이 없어 purge(deleteForNodes)가 영영 회수하지 못한다 —
 * 반복 실패 시 무한 누적이므로 실패 경로에서 직접 지운다.
 *
 * <p>실디스크로는 "채널은 열렸는데 쓰다가 실패"를 만들 수 없어, 그 상황을 재현하는 서브클래스로
 * {@code writeOwnerOnly}만 갈아끼운다. 나머지 경로(경로 계산·예외 변환·정리)는 실제 코드가 돈다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:attorphan?mode=memory&cache=shared",
    "worknote.upload.dir=build/test-attachment-orphan"
})
class AttachmentOrphanCleanupTest {
    @Autowired AttachmentMapper mapper;
    @Autowired SettingService settings;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private static final String UPLOAD_DIR = "build/test-attachment-orphan";

    /** 파일을 만든 뒤(= CREATE_NEW 성공) 쓰기 도중 터지는 상황. */
    private static class FailsMidWrite extends AttachmentService {
        Path attempted;

        FailsMidWrite(AttachmentMapper m, SettingService s, Clock c) {
            super(m, s, c, UPLOAD_DIR);
        }

        @Override
        void writeOwnerOnly(Path target, byte[] bytes) throws IOException {
            attempted = target;
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[]{1, 2}, StandardOpenOption.CREATE_NEW);   // 부분 기록
            throw new IOException("디스크 가득");
        }
    }

    @BeforeEach
    void seedNode() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id = 'n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('n1',NULL,'note','노트',1)");
    }

    @Test
    void partialFileIsRemovedAfterWriteFailure() {
        FailsMidWrite svc = new FailsMidWrite(mapper, settings, clock);

        assertThatThrownBy(() -> svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local"))
            .isInstanceOf(VaultException.class);

        assertThat(svc.attempted).isNotNull();
        assertThat(Files.exists(svc.attempted)).isFalse();
    }

    @Test
    void writeFailureLeavesNoDbRow() {
        FailsMidWrite svc = new FailsMidWrite(mapper, settings, clock);

        assertThatThrownBy(() -> svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local"))
            .isInstanceOf(VaultException.class);

        assertThat(svc.findByNode("n1")).isEmpty();
    }

    /**
     * 정리 자체가 실패해도 원래 원인이 가려지면 안 된다.
     * target 자리에 <b>비어있지 않은 디렉토리</b>를 만들어 {@code deleteIfExists}가 실제로 던지게 한다
     * (없는 파일을 지우는 건 조용히 성공하므로 그걸론 이 경로를 못 탄다).
     */
    @Test
    void cleanupFailureDoesNotMaskOriginalError() {
        FailsWithUndeletableTarget svc = new FailsWithUndeletableTarget(mapper, settings, clock);

        assertThatThrownBy(() -> svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local"))
            .isInstanceOf(VaultException.class)
            .hasMessage("파일을 저장하지 못했습니다");   // 정리 실패가 클라 메시지를 바꾸지 않는다

        assertThat(Files.isDirectory(svc.attempted)).isTrue();   // 정리는 실패했지만 기동/응답은 정상
    }

    /** target을 지울 수 없게 만든 뒤 쓰기 실패 — deleteIfExists가 DirectoryNotEmptyException을 던진다. */
    private static class FailsWithUndeletableTarget extends AttachmentService {
        Path attempted;

        FailsWithUndeletableTarget(AttachmentMapper m, SettingService s, Clock c) {
            super(m, s, c, UPLOAD_DIR);
        }

        @Override
        void writeOwnerOnly(Path target, byte[] bytes) throws IOException {
            attempted = target;
            Files.createDirectories(target.resolve("blocker"));
            throw new IOException("디스크 가득");
        }
    }
}
