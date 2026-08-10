package com.worknote.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 행 없는 첨부 파일(고아) 주기 회수. 스케줄 배선은 {@link AttachmentReapScheduler} — 로직 분리로 테스트 직접 호출.
 *
 * <p><b>왜 필요한가.</b> {@link AttachmentService#store}는 파일을 먼저 쓰고 DB 행을 나중에 커밋한다.
 * 쓰기 실패(catch에서 부분 파일 삭제)와 트랜잭션 롤백({@code deleteIfRolledBack})은 이미 덮여 있지만,
 * <b>그 사이에 프로세스가 죽는 창</b>은 어느 쪽도 못 덮는다. {@code delete()}/{@code deleteForNodes()}가
 * 파일 삭제 {@code IOException}을 의도적으로 삼키는 것도 같은 결과를 남긴다. 그렇게 남은 파일은
 * DB 기준으로 도는 purge가 영영 회수하지 못하므로, 디스크에서 출발하는 회수기가 유일한 회수 수단이다.
 *
 * <p><b>지우는 방향은 하나뿐이다.</b> 행 없는 파일만 지운다. 파일 없는 행은 <b>절대 지우지 않는다</b> —
 * 그건 반대 방향의 실패(메타 유실)고 이 잡의 일이 아니다. 다만 실제 데이터 유실 신호이므로 세어서 WARN으로 알린다.
 */
@Service
public class AttachmentReapService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentReapService.class);

    /**
     * {@code store()}가 쓰는 유일한 모양: {@code <2hex>/<2hex>/<32hex>}, 앞 4자는 파일명의 접두와 같다
     * (셋 다 같은 UUID에서 나온다). 소문자만 — {@code UUID.toString()}은 항상 소문자다.
     *
     * <p>{@code WORKNOTE_UPLOAD_DIR}는 운영자가 주는 경로라 앱 전용이라는 보장이 없다({@code /data},
     * {@code /srv/shared}일 수 있다). 모양이 다른 것은 우리가 만든 것이 아니므로 손대지 않는다 —
     * {@code StoragePermissions}의 "앱이 만든 것만 앱이 바꾼다"와 같은 원칙이고, 회수기에선 이 원칙을 어기는 대가가
     * "남의 파일 삭제"라 특히 크다. 좁게 잡아 못 지우는 쪽은 디스크만 남지만, 넓게 잡아 잘못 지우면 복구가 없다.
     */
    private static final Pattern SHARD_NAME = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SHARD_DIR = Pattern.compile("[0-9a-f]{2}");

    private final AttachmentMapper mapper;
    private final Clock clock;
    private final Path root;
    private final int graceHours;

    public AttachmentReapService(AttachmentMapper mapper, Clock clock,
                                 @Value("${worknote.upload.dir:./attachments}") String uploadDir,
                                 @Value("${worknote.attachment.reap.grace-hours:24}") int graceHours) {
        this.mapper = mapper;
        this.clock = clock;
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.graceHours = graceHours;
    }

    /** @param deletedFiles 지운 고아 파일 수, @param reclaimedBytes 회수한 바이트, @param missingFiles 파일이 사라진 행 수 */
    public record ReapResult(int deletedFiles, long reclaimedBytes, int missingFiles) {
        static final ReapResult NONE = new ReapResult(0, 0, 0);
    }

    /**
     * 고아 파일을 회수한다. grace-hours 0 이하 = 비활성.
     *
     * <p>트랜잭션 없이 돈다 — 파일 삭제는 트랜잭션 작업이 아니고, 디스크 삭제를 DB 트랜잭션으로 감싸면
     * 롤백돼도 파일은 돌아오지 않으면서 SQLite 단일 라이터만 붙잡는다. rel_path 조회는 읽기 한 방이면 충분하다.
     */
    public ReapResult reapOrphans() {
        if (graceHours <= 0) {
            return ReapResult.NONE;
        }
        if (!Files.isDirectory(root)) {
            return ReapResult.NONE;   // 업로드가 한 번도 없었던 설치 — 회수할 것도 없다
        }
        // 유예 기준선. mtime은 OS 벽시계이므로 앱 시계도 실시간(systemDefaultZone)이어야 의미가 맞는다.
        Instant cutoff = Instant.now(clock).minusSeconds(graceHours * 3600L);

        // 파일 목록을 먼저, DB를 나중에 읽는다. 반대로 하면 두 시점 사이에 커밋된 업로드가 "행 없는 파일"로 보인다
        // (유예가 이미 그 창을 덮지만, 순서까지 안전한 쪽으로 두어 유예 하나에만 기대지 않는다).
        // (파일이 하나도 없어도 조회는 건너뛰지 않는다 — "파일 없는 행"은 그때가 오히려 전부다)
        List<Path> candidates = listOurFiles();
        Set<String> referenced = new HashSet<>(mapper.findAllRelPaths());

        int deleted = 0;
        long bytes = 0;
        for (Path file : candidates) {
            String rel = relPathOf(file);
            if (referenced.contains(rel)) {
                continue;
            }
            if (!file.normalize().startsWith(root)) {
                continue;   // 루트 밖은 어떤 경우에도 지우지 않는다 (pathOf의 봉쇄 관용구와 대칭)
            }
            try {
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) {
                    continue;   // 유예 이내 — 방금 쓰이고 아직 커밋 전인 업로드일 수 있다
                }
                long size = Files.size(file);
                if (!Files.deleteIfExists(file)) {
                    continue;   // size와 delete 사이에 사라짐 — 우리가 회수한 것이 아니므로 세지 않는다
                }
                deleted++;
                bytes += size;
                log.debug("고아 첨부 회수: {} ({}바이트)", rel, size);
            } catch (NoSuchFileException gone) {
                // 스캔 중 사용자가 첨부를 지운 것(delete()는 행→파일 순) — 파일이 없어진 건 원하는 최종 상태지 실패가 아니다.
                // 여기서 WARN을 남기면 정상 조작이 매번 경고가 되고, 운영자는 결국 이 로거 전체를 무시하게 된다.
                log.debug("회수 전에 이미 사라짐: {}", rel);
            } catch (IOException e) {
                // 권한·IO 오류 등 사람이 실제로 손봐야 하는 경우만 남긴다. 한 파일 실패가 나머지를 막지는 않는다.
                log.warn("고아 첨부 회수 실패: {}", rel, e);
            }
        }

        // 파일 검사 뒤 rel_path를 한 번 더 읽는다 — 위 스캔 도중 사용자가 지운 첨부는 두 번째 스냅샷에서 사라지므로
        // "파일 없는 행"으로 오인되지 않는다(위 NoSuchFile 처리와 같은 경합의 반대 방향).
        int missing = countMissingFiles(referenced, new HashSet<>(mapper.findAllRelPaths()));
        if (deleted > 0) {
            log.info("고아 첨부 회수: {}건 {}바이트", deleted, bytes);
        }
        if (missing > 0) {
            // 파일 없는 행 = 실제 데이터 유실 신호. 지우지는 않되 운영자가 볼 수 있게 남긴다.
            log.warn("첨부 파일이 사라진 메타 {}건 — 행은 그대로 둡니다(백업 확인 필요)", missing);
        }
        return new ReapResult(deleted, bytes, missing);
    }

    /**
     * 루트 아래에서 <b>우리가 만든 모양</b>의 정규 파일만 모은다.
     *
     * <p>{@code Files.walk}는 기본적으로 심링크를 따라가지 않는다 — 루트 안의 심링크 디렉토리가 walk를 밖으로
     * 데려가지 못한다. 심링크 파일 자체도 {@code NOFOLLOW_LINKS} 정규 파일 검사에서 걸러진다(링크를 지우는 건
     * 안전하더라도, 우리가 만들지 않은 것을 건드리지 않는다는 원칙이 우선이다).
     *
     * <p><b>빈 샤드 디렉토리는 지우지 않는다.</b> 깔끔해 보이지만 경합한다: {@code store()}는
     * {@code createDirectories(parent)} 직후 {@code CREATE_NEW}로 파일을 여는데, 그 사이에 빈 샤드를 지우면
     * 사용자의 업로드가 실패한다. 빈 700 디렉토리는 4KB일 뿐 보안·정합성 문제가 없다 —
     * 사용자에게 보이는 업로드 실패와 4KB를 맞바꾸는 건 나쁜 거래다. "정리"하지 말 것.
     */
    List<Path> listOurFiles() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 3)) {   // 우리 파일은 정확히 깊이 3 — 더 내려갈 이유가 없다
            walk.filter(this::isOurShardPath)
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .forEach(found::add);
        } catch (IOException e) {
            log.warn("업로드 루트 스캔 실패: {}", root, e);
        }
        return found;
    }

    /** 루트 기준 상대 경로가 정확히 {@code <2hex>/<2hex>/<32hex>}이고 샤드가 이름의 접두 4자와 일치하는가. */
    private boolean isOurShardPath(Path file) {
        Path rel = root.relativize(file);
        if (rel.getNameCount() != 3) {
            return false;
        }
        String d1 = rel.getName(0).toString();
        String d2 = rel.getName(1).toString();
        String name = rel.getName(2).toString();
        return SHARD_DIR.matcher(d1).matches()
            && SHARD_DIR.matcher(d2).matches()
            && SHARD_NAME.matcher(name).matches()
            && name.startsWith(d1 + d2);
    }

    /** DB의 rel_path는 항상 '/' 구분 — 플랫폼 구분자에 기대지 않고 조립한다. */
    private String relPathOf(Path file) {
        Path rel = root.relativize(file);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i));
        }
        return sb.toString();
    }

    /**
     * 행은 있는데 파일이 없는 경우를 센다. <b>세기만 한다</b> — 행은 어떤 경우에도 건드리지 않는다.
     *
     * @param referenced      파일 스캔 직후의 rel_path 스냅샷
     * @param stillReferenced 검사 시점의 rel_path 스냅샷. 그 사이 사라진 행은 유실이 아니라 사용자의 정상 삭제다
     */
    int countMissingFiles(Set<String> referenced, Set<String> stillReferenced) {
        int missing = 0;
        for (String rel : referenced) {
            if (!stillReferenced.contains(rel)) {
                continue;
            }
            Path p = root.resolve(rel).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) {
                missing++;
                log.debug("첨부 파일 없음: {}", rel);
            }
        }
        return missing;
    }
}
