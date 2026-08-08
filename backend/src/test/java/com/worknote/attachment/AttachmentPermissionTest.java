package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M-6 — 첨부는 생성 시점에 600. 넓게 만들고 나중에 chmod하면 그 사이 창이 열린다.
 * 샤딩 디렉토리도 700으로 만들어져야 파일 권한이 무의미해지지 않는다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:attperm?mode=memory&cache=shared",
    "worknote.upload.dir=build/test-attachment-perms"
})
class AttachmentPermissionTest {
    @Autowired AttachmentService svc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedNode() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id = 'n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('n1',NULL,'note','노트',1)");
    }

    @Test
    void storedFileIsOwnerOnly() throws Exception {
        AttachmentRow row = svc.store("n1", "a.png", new byte[]{1, 2, 3}, "local");
        Path file = svc.pathOf(row);
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX 미지원 — skip");
        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void shardDirectoriesAreOwnerOnly() throws Exception {
        AttachmentRow row = svc.store("n1", "b.png", new byte[]{9}, "local");
        Path file = svc.pathOf(row);
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX 미지원 — skip");
        assertThat(Files.getPosixFilePermissions(file.getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file.getParent().getParent()))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void uploadRootIsOwnerOnlyAtStartup() throws Exception {
        Path root = Path.of("build/test-attachment-perms").toAbsolutePath().normalize();
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX 미지원 — skip");
        assertThat(Files.isDirectory(root)).isTrue();
        assertThat(Files.getPosixFilePermissions(root))
            .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void storedContentIsIntact() {
        AttachmentRow row = svc.store("n1", "c.png", new byte[]{1, 2, 3, 4, 5}, "local");
        assertThat(svc.read(row)).containsExactly(1, 2, 3, 4, 5);
    }
}
