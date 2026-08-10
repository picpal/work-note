package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 고아 첨부 파일 회수기. 프로세스가 파일 기록과 DB 커밋 사이에서 죽으면 행 없는 파일이 남는데
 * {@code store()}의 실패 경로도 {@code deleteIfRolledBack}도 그 창을 못 덮는다 — 주기 회수가 유일한 회수 수단이다.
 *
 * <p>업로드 루트는 테스트마다 {@code @TempDir}로 격리한다. 회수기는 <b>지우는</b> 쪽이라
 * 공유 디렉토리(build/test-attachments 등)에서 돌리면 형제 테스트의 파일을 지울 수 있다.
 * DB는 클래스 전용 인메모리(attreap) — 익명 {@code file::memory:}는 JVM 전역 공유라 노드 id가 형제와 충돌한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:attreap?mode=memory&cache=shared",
    "worknote.upload.dir=build/test-attachment-reap"
})
class AttachmentReapServiceTest {
    @Autowired AttachmentMapper mapper;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private static final int GRACE_HOURS = 24;

    @BeforeEach
    void seedNode() {
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM node WHERE id = 'rp-n1'");
        jdbc.update("INSERT INTO node(id,parent_id,type,name,position) VALUES('rp-n1',NULL,'note','노트',1)");
    }

    private AttachmentReapService reaper(Path root, int graceHours) {
        return new AttachmentReapService(mapper, clock, root.toString(), graceHours);
    }

    /**
     * 로그를 "안 남겼다"까지 봐야 해서 appender를 붙인다 (StoragePermissionGuardTest와 같은 관례) —
     * 정상 조작이 만드는 WARN은 운영자를 로그에 무감각하게 만든다.
     */
    private <T> LoggedRun<T> captureLogs(Supplier<T> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AttachmentReapService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            return new LoggedRun<>(action.get(), appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private record LoggedRun<T>(T value, List<ILoggingEvent> logs) {
        List<ILoggingEvent> warnings() {
            return logs.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        }
    }

    /** {@code store()}가 쓰는 것과 같은 모양: {@code <2hex>/<2hex>/<32hex>}. */
    private Path shard(Path root, String uuid32, int bytes) throws IOException {
        return file(root, uuid32.substring(0, 2) + "/" + uuid32.substring(2, 4) + "/" + uuid32, bytes);
    }

    private Path file(Path root, String relPath, int bytes) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        return p;
    }

    /** 잠들지 않고 나이를 만든다 — mtime을 직접 과거로 민다. */
    private void aged(Path file, Duration age) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now(clock).minus(age)));
    }

    /**
     * 앱 시계가 아니라 <b>실제 벽시계</b>로 나이를 만든다 — 유예 판정이 OS mtime과 같은 시간축에 있는지 확인하는 지뢰선.
     *
     * <p>{@link #aged}는 앱 시계 기준이라 시계가 고정돼도 양변이 같이 밀려 테스트가 공허하게 통과한다.
     * 이 헬퍼를 쓰는 테스트는 {@code Clock} 빈이 고정 시계로 바뀌는 순간 실패한다 — 그때 리퍼는
     * (과거 고정) 영영 아무것도 회수하지 않거나 (미래 고정) 방금 올린 파일까지 지운다. 조용히 넘어가면 안 되는 변경이다.
     */
    private void agedByWallClock(Path file, Duration age) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
    }

    private void insertRow(String id, String relPath, int size) {
        jdbc.update("INSERT INTO attachment(id,node_id,filename,ext,mime,size,rel_path,created_by,created_at) "
            + "VALUES(?,'rp-n1','a.png','png','image/png',?,?,'local','2026-08-01T00:00:00')", id, size, relPath);
    }

    @Test
    void 유예를_지난_고아_파일은_삭제된다(@TempDir Path root) throws Exception {
        Path orphan = shard(root, "aa11bb22cc33dd44ee55ff6677889900", 10);
        agedByWallClock(orphan, Duration.ofHours(GRACE_HOURS + 1));

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(Files.exists(orphan)).isFalse();
        assertThat(r.deletedFiles()).isEqualTo(1);
    }

    @Test
    void 유예_이내_고아_파일은_남는다(@TempDir Path root) throws Exception {
        // 업로드는 파일을 쓰고 몇 ms 뒤 행을 커밋한다 — 그 창(긴 트랜잭션·시계 오차 포함)을 유예가 덮는다
        Path fresh = shard(root, "bb11bb22cc33dd44ee55ff6677889900", 10);
        aged(fresh, Duration.ofHours(GRACE_HOURS - 1));

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(Files.exists(fresh)).isTrue();
        assertThat(r.deletedFiles()).isZero();
    }

    @Test
    void DB_행이_있는_파일은_아무리_오래돼도_건드리지_않는다(@TempDir Path root) throws Exception {
        String uuid = "cc11bb22cc33dd44ee55ff6677889900";
        String rel = uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid;
        Path kept = shard(root, uuid, 10);
        aged(kept, Duration.ofDays(3650));
        insertRow("att-" + uuid, rel, 10);

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(Files.exists(kept)).isTrue();
        assertThat(r.deletedFiles()).isZero();
        assertThat(r.missingFiles()).isZero();
    }

    @Test
    void 샤드_모양이_아닌_파일은_오래된_미참조여도_건드리지_않는다(@TempDir Path root) throws Exception {
        // WORKNOTE_UPLOAD_DIR는 운영자가 주는 경로 — 우리 것이 아닌 파일이 섞여 있을 수 있다
        Path[] notOurs = {
            file(root, "README.txt", 1),                                          // 루트 직속
            file(root, "notes/mine.txt", 1),                                      // 남의 하위 트리
            file(root, "aa/11/aa11bb22cc33dd44ee55ff667788990", 1),               // 31 hex
            file(root, "aa/11/aa11bb22cc33dd44ee55ff66778899001", 1),             // 33 hex
            file(root, "AA/11/AA11BB22CC33DD44EE55FF6677889900", 1),              // 대문자 hex (UUID는 소문자)
            file(root, "aa/11/aa11bb22cc33dd44ee55ff66778899zz", 1),              // hex 아님
            file(root, "zz/99/aa11bb22cc33dd44ee55ff6677889900", 1),              // 샤드가 이름의 접두 4자와 불일치
            file(root, "aa/11/22/aa11bb22cc33dd44ee55ff6677889900", 1),           // 깊이 4
            file(root, "aa11bb22cc33dd44ee55ff6677889900", 1),                    // 깊이 1
        };
        for (Path p : notOurs) {
            aged(p, Duration.ofDays(365));
        }

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        for (Path p : notOurs) {
            assertThat(Files.exists(p)).as("건드리면 안 되는 파일: %s", root.relativize(p)).isTrue();
        }
        assertThat(r.deletedFiles()).isZero();
    }

    @Test
    void 파일이_사라진_행은_지우지_않고_이상치로만_센다(@TempDir Path root) {
        // 반대 방향 실패(메타 유실)는 이 잡의 일이 아니다 — 운영자가 볼 수 있게 세기만 한다
        String uuid = "dd11bb22cc33dd44ee55ff6677889900";
        String rel = uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid;
        insertRow("att-" + uuid, rel, 10);

        LoggedRun<AttachmentReapService.ReapResult> run =
            captureLogs(() -> reaper(root, GRACE_HOURS).reapOrphans());

        assertThat(mapper.findById("att-" + uuid)).isNotNull();
        assertThat(run.value().missingFiles()).isEqualTo(1);
        assertThat(run.value().deletedFiles()).isZero();
        // 진짜 유실은 계속 WARN이어야 한다 — 아래 경합 테스트가 이 경고까지 죽이면 안 된다
        assertThat(run.warnings()).singleElement().satisfies(
            e -> assertThat(e.getFormattedMessage()).contains("사라진 메타"));
    }

    /**
     * 스캔 중 사용자가 첨부를 지우는 정상 조작 — {@code delete()}는 행을 지우고 파일을 지우므로,
     * 목록에 잡힌 뒤 처리 전에 파일이 사라진다. 이건 원하는 최종 상태이지 실패가 아니다.
     *
     * <p>목록과 삭제 사이라는 시점을 만들 유일한 정직한 방법이라 {@code listOurFiles}만 갈아끼운다
     * (AttachmentOrphanCleanupTest가 {@code writeOwnerOnly}에 쓰는 것과 같은 이음매).
     * 나머지 경로(유예 판정·rel 계산·삭제·집계)는 실제 코드가 돈다.
     */
    private static class VanishesAfterListing extends AttachmentReapService {
        VanishesAfterListing(AttachmentMapper m, Clock c, String root, int graceHours) {
            super(m, c, root, graceHours);
        }

        @Override
        List<Path> listOurFiles() {
            List<Path> found = super.listOurFiles();
            for (Path p : found) {
                try {
                    Files.deleteIfExists(p);   // 사용자의 첨부 삭제가 스캔과 겹쳤다
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return found;
        }
    }

    @Test
    void 스캔_중_사라진_후보는_경고도_집계도_없이_넘어간다(@TempDir Path root) throws Exception {
        Path racing = shard(root, "2a11bb22cc33dd44ee55ff6677889900", 10);
        aged(racing, Duration.ofDays(2));

        LoggedRun<AttachmentReapService.ReapResult> run = captureLogs(
            () -> new VanishesAfterListing(mapper, clock, root.toString(), GRACE_HOURS).reapOrphans());

        assertThat(Files.exists(racing)).isFalse();
        assertThat(run.value().deletedFiles()).isZero();       // 우리가 회수한 것이 아니다
        assertThat(run.value().reclaimedBytes()).isZero();
        assertThat(run.warnings()).isEmpty();                  // 정상 조작이 경고를 만들면 안 된다
    }

    @Test
    void 스캔_중_지워진_행은_파일없는_메타로_세지_않는다() {
        // 대칭 경합: 스냅샷 이후 사용자가 행+파일을 지우면 "파일 없는 행"처럼 보인다.
        // 재조회에서 사라진 rel_path는 유실이 아니라 정상 삭제다.
        AttachmentReapService reaper = new AttachmentReapService(mapper, clock, "build/unused-root", GRACE_HOURS);

        assertThat(reaper.countMissingFiles(Set.of("aa/bb/aabb0011223344556677889900aabb"), Set.of()))
            .as("재조회에 없는 행 = 그 사이 정상 삭제됨").isZero();
        assertThat(reaper.countMissingFiles(Set.of("aa/bb/aabb0011223344556677889900aabb"),
            Set.of("aa/bb/aabb0011223344556677889900aabb")))
            .as("두 스냅샷에 다 있는데 파일이 없으면 진짜 유실").isEqualTo(1);
    }

    @Test
    void 유예시간이_0_이하면_리퍼가_통째로_꺼진다(@TempDir Path root) throws Exception {
        Path orphan = shard(root, "ee11bb22cc33dd44ee55ff6677889900", 10);
        aged(orphan, Duration.ofDays(365));

        AttachmentReapService.ReapResult r = reaper(root, 0).reapOrphans();

        assertThat(Files.exists(orphan)).isTrue();
        assertThat(r.deletedFiles()).isZero();
        assertThat(r.missingFiles()).isZero();
    }

    @Test
    void 루트_안의_심링크는_밖의_파일을_지우지_못한다(@TempDir Path root, @TempDir Path outside) throws Exception {
        Path victimDir = outside.resolve("cd");
        Files.createDirectories(victimDir);
        Path victim = victimDir.resolve("ab11bb22cc33dd44ee55ff6677889900");
        Files.write(victim, new byte[10]);
        aged(victim, Duration.ofDays(365));
        Files.createSymbolicLink(root.resolve("ab"), outside);        // 샤드 1단이 통째로 링크

        Path victim2 = outside.resolve("victim.bin");
        Files.write(victim2, new byte[10]);
        aged(victim2, Duration.ofDays(365));
        Files.createDirectories(root.resolve("ef/12"));
        Files.createSymbolicLink(root.resolve("ef/12/ef12bb22cc33dd44ee55ff6677889900"), victim2);   // 파일 링크

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(Files.exists(victim)).isTrue();
        assertThat(Files.exists(victim2)).isTrue();
        assertThat(Files.exists(root.resolve("ef/12/ef12bb22cc33dd44ee55ff6677889900"),
            LinkOption.NOFOLLOW_LINKS)).isTrue();   // 링크 자체도 정규 파일이 아니므로 대상이 아니다
        assertThat(r.deletedFiles()).isZero();
    }

    @Test
    void 회수한_바이트를_합산해_돌려준다(@TempDir Path root) throws Exception {
        aged(shard(root, "0a11bb22cc33dd44ee55ff6677889900", 100), Duration.ofDays(2));
        aged(shard(root, "0b11bb22cc33dd44ee55ff6677889900", 250), Duration.ofDays(2));
        aged(shard(root, "0c11bb22cc33dd44ee55ff6677889900", 7), Duration.ofMinutes(1));   // 유예 이내
        String uuid = "0d11bb22cc33dd44ee55ff6677889900";
        Path referenced = shard(root, uuid, 999);
        aged(referenced, Duration.ofDays(2));
        insertRow("att-" + uuid, uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid, 999);

        AttachmentReapService.ReapResult r = reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(r.deletedFiles()).isEqualTo(2);
        assertThat(r.reclaimedBytes()).isEqualTo(350);
        assertThat(Files.exists(referenced)).isTrue();
    }

    @Test
    void 업로드_루트가_아직_없으면_조용히_넘어간다(@TempDir Path root) {
        AttachmentReapService.ReapResult r = reaper(root.resolve("아직-없음"), GRACE_HOURS).reapOrphans();

        assertThat(r.deletedFiles()).isZero();
        assertThat(r.reclaimedBytes()).isZero();
    }

    @Test
    void 빈_샤드_디렉토리는_남긴다(@TempDir Path root) throws Exception {
        // store()의 createDirectories→CREATE_NEW 사이에 샤드를 지우면 업로드가 실패한다 (AttachmentReapService 주석)
        Path orphan = shard(root, "1a11bb22cc33dd44ee55ff6677889900", 10);
        aged(orphan, Duration.ofDays(2));

        reaper(root, GRACE_HOURS).reapOrphans();

        assertThat(Files.isDirectory(orphan.getParent())).isTrue();
        assertThat(Files.isDirectory(orphan.getParent().getParent())).isTrue();
    }
}
