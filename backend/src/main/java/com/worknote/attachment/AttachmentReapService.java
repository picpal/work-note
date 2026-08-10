package com.worknote.attachment;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
 *
 * <p><b>보호는 문자열이 아니라 파일 정체성으로 한다.</b> rel_path 문자열 비교만으로 보호하면
 * 비정규 행({@code aa/11/../11/이름})이나 대소문자 변형처럼 <i>같은 파일을 가리키지만 철자가 다른</i> 행이
 * 보호에서 빠진다 — {@code pathOf}로는 정상 열람되는 파일이 회수기에겐 고아로 보인다.
 * 그래서 참조된 경로를 stat해 {@code fileKey}(inode 정체성)까지 보호 집합에 넣는다.
 *
 * <p><b>기본값은 꺼짐이다. 이유가 있다.</b> 위 가드는 전부 "무엇을 지울지"를 좁히지만,
 * <b>루트가 우리 것인지</b>는 프로세스 안에서 알 수가 없다. {@code WORKNOTE_UPLOAD_DIR}는 운영자가 주는
 * 경로고, 우리 모양({@code <2hex>/<2hex>/<32hex>}, 이름이 샤드 접두로 시작)은 <b>MD5 기반 저장소의 흔한
 * 레이아웃과 그대로 겹친다</b>({@code md5[0:2]/md5[2:4]/md5}). 그런 저장소를 같은 루트에 두면 남의 파일이
 * 회수 대상이 된다.
 *
 * <p>마커 파일로 전용성을 판정하는 방법을 검토했으나 <b>바로 그 경우를 못 막는다</b> — MD5 저장소의 루트도
 * {@code <2hex>} 디렉토리만 들어 있어 우리 루트와 구분되지 않으므로 마커가 그대로 생긴다. 안에서 증명할 수
 * 없는 것을 증명한 척하는 판정을 넣는 대신, <b>운영자에게 단언을 요구한다</b>:
 * {@code WORKNOTE_ATTACHMENT_REAP_GRACE_HOURS}를 양수로 설정하는 행위가 "이 루트는 이 앱 전용"이라는 선언이다.
 * 켤 때 그 전제를 기동 로그에 다시 남겨, 설정을 물려받은 사람도 무엇을 단언한 것인지 보게 한다.
 *
 * <p>회수하는 누수는 강제 종료 중 업로드라는 드문 사건이라 껐을 때의 비용은 디스크 몇 개다.
 * 잘못 지웠을 때의 비용은 복구가 없다. 비대칭이 이 기본값을 정한다.
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
     *
     * <p>다만 <b>모양은 소유의 증명이 아니다</b> — 같은 {@code 2/2/hash} 레이아웃을 쓰는 다른 저장소를
     * 같은 루트에 두면 여전히 남의 파일이 대상이 된다. 그 위험은 이 상수가 아니라 루트 전용성 판정으로 막아야 한다.
     */
    private static final Pattern SHARD_NAME = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SHARD_DIR = Pattern.compile("[0-9a-f]{2}");

    /** 이보다 짧은 유예는 정상 업로드를 지울 수 있다 — 기동 시 경고만 하고 막지는 않는다(운영 튜닝 값). */
    static final int UNSAFE_GRACE_HOURS = 6;

    private final AttachmentMapper mapper;
    private final Clock clock;
    private final Path root;
    private final int graceHours;

    public AttachmentReapService(AttachmentMapper mapper, Clock clock,
                                 @Value("${worknote.upload.dir:./attachments}") String uploadDir,
                                 @Value("${worknote.attachment.reap.grace-hours:0}") int graceHours) {
        this.mapper = mapper;
        this.clock = clock;
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.graceHours = graceHours;
        if (graceHours > 0) {
            // 켠 것 자체가 "이 루트는 이 앱 전용"이라는 단언이다(클래스 주석 참조). 설정을 물려받은 운영자도
            // 무엇을 단언한 것인지 보도록 매 기동에 남긴다 — 이건 잡음이 아니라 파괴적 동작의 전제다.
            log.info("고아 첨부 회수 활성({}시간 유예). 전제: {} 는 이 앱 전용 디렉토리입니다 — "
                + "다른 시스템의 파일이 섞여 있으면 삭제될 수 있습니다.", graceHours, root);
        }
        if (graceHours > 0 && graceHours < UNSAFE_GRACE_HOURS) {
            // 기동을 세우지는 않는다 — 보안 통제가 아니라 튜닝 값이고, 이것 때문에 서비스가 안 뜨는 편이 더 나쁘다.
            log.warn("WORKNOTE_ATTACHMENT_REAP_GRACE_HOURS={} 은(는) 너무 짧습니다 — 업로드가 파일 기록 후 커밋 전에 "
                + "회수되면 파일 없는 행(이 리퍼가 탐지하려는 바로 그 데이터 유실)이 생깁니다. 권장 {}시간 이상.",
                graceHours, UNSAFE_GRACE_HOURS);
        }
    }

    /**
     * 회수기가 켜져 있는가. 기본은 꺼짐 — 켜는 것은 루트 전용성에 대한 운영자의 단언이다(클래스 주석 참조).
     * 설정 → {@code @Value} 기본값 → 여기까지의 사슬 전체를 테스트가 이 메서드 하나로 고정한다.
     */
    boolean enabled() {
        return graceHours > 0;
    }

    /** @param deletedFiles 지운 고아 파일 수, @param reclaimedBytes 회수한 바이트, @param missingFiles 파일이 사라진 행 수 */
    public record ReapResult(int deletedFiles, long reclaimedBytes, int missingFiles) {
        static final ReapResult NONE = new ReapResult(0, 0, 0);
    }

    /** 회수 후보 한 건. {@code fileKey}는 워크 중 얻은 정체성 — 철자가 아니라 실체로 보호 여부를 판정한다. */
    record Candidate(Path path, String rel, Object fileKey) {}

    /** 참조된 rel_path를 stat한 결과 — 보호할 정체성 집합과, 파일이 없던 rel_path 집합. */
    private record Referenced(Set<Object> protectedKeys, Set<String> missingRels) {}

    /**
     * 고아 파일을 회수한다. grace-hours 0 이하 = 비활성.
     *
     * <p>트랜잭션 없이 돈다 — 파일 삭제는 트랜잭션 작업이 아니고, 디스크 삭제를 DB 트랜잭션으로 감싸면
     * 롤백돼도 파일은 돌아오지 않으면서 SQLite 단일 라이터만 붙잡는다. rel_path 조회는 읽기 한 방이면 충분하다.
     */
    public ReapResult reapOrphans() {
        if (!enabled()) {
            return ReapResult.NONE;
        }
        Path scanRoot;
        try {
            // 루트가 심링크(별도 볼륨 마운트 — StoragePermissions "원칙 2"가 명시적으로 지원하는 배치)면
            // walk가 링크 자신 하나만 내놓고 끝난다. 매 스캔마다 실경로를 푼다 — 생성자에서 한 번 풀어두면
            // 두 스캔 사이에 링크가 다른 곳을 가리키도록 바뀌었을 때 엉뚱한 트리를 지우게 된다.
            scanRoot = root.toRealPath();
        } catch (IOException unavailable) {
            return rootUnavailable();
        }
        if (!Files.isDirectory(scanRoot)) {
            return rootUnavailable();
        }
        // 유예 기준선. mtime은 OS 벽시계이므로 앱 시계도 실시간(systemDefaultZone)이어야 의미가 맞는다.
        Instant cutoff = Instant.now(clock).minusSeconds(graceHours * 3600L);

        // 파일 목록을 먼저, DB를 나중에 읽는다. 반대로 하면 두 시점 사이에 커밋된 업로드가 "행 없는 파일"로 보인다
        // (유예가 이미 그 창을 덮지만, 순서까지 안전한 쪽으로 두어 유예 하나에만 기대지 않는다).
        // (파일이 하나도 없어도 조회는 건너뛰지 않는다 — "파일 없는 행"은 그때가 오히려 전부다)
        List<Candidate> candidates = listOurFiles(scanRoot);
        Set<String> referenced = new HashSet<>(mapper.findAllRelPaths());
        // 참조 경로를 한 번만 stat해서 두 가지를 동시에 얻는다: 보호할 정체성, 그리고 파일이 없는 행.
        Referenced ref = statReferenced(scanRoot, referenced);

        int deleted = 0;
        long bytes = 0;
        for (Candidate c : candidates) {
            if (referenced.contains(c.rel())) {
                continue;   // 철자 일치 — 가장 흔한 경로
            }
            if (c.fileKey() != null && ref.protectedKeys().contains(c.fileKey())) {
                continue;   // 철자는 달라도 같은 실체를 가리키는 행이 있다(비정규 경로·하드링크·대소문자 무시 FS)
            }
            // 렉시컬 봉쇄 — walk가 이미 렉시컬 자손만 내놓으므로 여기서 새로 막아주는 건 사실상 없고,
            // 심링크 추적을 막는 것도 이 검사가 아니라 walk의 링크 미추적이다. 그럼에도 남겨둔다:
            // AttachmentService.pathOf와 같은 관용구라 옆의 진짜 가드까지 "정리"당하지 않게 하는 표식이다.
            // (열거~삭제 사이에 샤드 디렉토리를 심링크로 바꿔치기하는 TOCTOU는 SecureDirectoryStream이 필요하지만
            //  도입하지 않는다 — 루트는 700·앱 소유라 그 바꿔치기가 가능한 주체는 앱 계정과 root뿐이고,
            //  둘 다 이미 앱이 지울 수 있는 것을 직접 지울 수 있다. 공격이 요구하는 권한이 공격의 성과와 같다.)
            if (!c.path().normalize().startsWith(scanRoot)) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(c.path(), LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) {
                    continue;   // 유예 이내 — 방금 쓰이고 아직 커밋 전인 업로드일 수 있다
                }
                long size = Files.size(c.path());
                if (!Files.deleteIfExists(c.path())) {
                    continue;   // size와 delete 사이에 사라짐 — 우리가 회수한 것이 아니므로 세지 않는다
                }
                deleted++;
                bytes += size;
                log.debug("고아 첨부 회수: {} ({}바이트)", c.rel(), size);
            } catch (NoSuchFileException gone) {
                // 스캔 중 사용자가 첨부를 지운 것(delete()는 행→파일 순) — 파일이 없어진 건 원하는 최종 상태지 실패가 아니다.
                // 여기서 WARN을 남기면 정상 조작이 매번 경고가 되고, 운영자는 결국 이 로거 전체를 무시하게 된다.
                log.debug("회수 전에 이미 사라짐: {}", c.rel());
            } catch (IOException e) {
                // 권한·IO 오류 등 사람이 실제로 손봐야 하는 경우만 남긴다. 한 파일 실패가 나머지를 막지는 않는다.
                log.warn("고아 첨부 회수 실패: {}", c.rel(), e);
            }
        }

        // 파일 검사 뒤 rel_path를 한 번 더 읽는다 — 위 스캔 도중 사용자가 지운 첨부는 두 번째 스냅샷에서 사라지므로
        // "파일 없는 행"으로 오인되지 않는다(위 NoSuchFile 처리와 같은 경합의 반대 방향).
        // 두 스냅샷을 다 통과한 뒤 μs 단위로 지워지는 잔여 창은 남는다 — 하루 한 번 도는 잡에 3차 검사를 더할 값이 아니다.
        int missing = countMissing(ref.missingRels(), new HashSet<>(mapper.findAllRelPaths()));
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
     * 업로드 루트에 접근할 수 없을 때. 첨부 메타가 하나라도 있으면 <b>이 잡이 낼 수 있는 가장 큰 경고</b>다 —
     * 볼륨이 빠졌거나 경로가 바뀌었다는 뜻이고, 그 사이 제품의 첨부는 전부 열리지 않는다.
     * 업로드가 한 번도 없던 설치에선 조용히 넘어간다(정상 상태를 경고로 만들지 않는다).
     */
    private ReapResult rootUnavailable() {
        int rows = mapper.findAllRelPaths().size();
        if (rows > 0) {
            log.warn("업로드 루트에 접근할 수 없습니다: {} — 첨부 메타 {}건이 모두 열리지 않는 상태입니다. "
                + "볼륨 마운트나 WORKNOTE_UPLOAD_DIR 경로를 확인하세요.", root, rows);
        }
        return new ReapResult(0, 0, rows);
    }

    /**
     * 참조된 rel_path를 한 번씩 stat한다 — 보호할 {@code fileKey}와 "파일이 없는 행"을 같은 패스에서 얻는다.
     * 어느 경우에도 <b>행은 건드리지 않는다</b>.
     */
    private Referenced statReferenced(Path scanRoot, Set<String> referenced) {
        Set<Object> keys = new HashSet<>();
        Set<String> missing = new HashSet<>();
        for (String rel : referenced) {
            Path p;
            try {
                p = scanRoot.resolve(rel).normalize();
            } catch (InvalidPathException e) {
                missing.add(rel);   // DB 손상 — 셀 수는 있어도 보호할 실체가 없다
                continue;
            }
            if (!p.startsWith(scanRoot)) {
                missing.add(rel);   // 루트 밖을 가리키는 행(손상·조작) — pathOf도 거부하는 경로다
                continue;
            }
            try {
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                if (!attrs.isRegularFile()) {
                    missing.add(rel);
                    continue;
                }
                Object key = attrs.fileKey();
                if (key != null) {
                    keys.add(key);   // null이면(일부 파일시스템) 문자열 일치 보호만 남는다 — 이중 방어의 나머지 한 겹
                }
            } catch (IOException e) {
                missing.add(rel);
                log.debug("첨부 파일 없음: {}", rel);
            }
        }
        return new Referenced(keys, missing);
    }

    /**
     * 루트 아래에서 <b>우리가 만든 모양</b>의 정규 파일만 모은다.
     *
     * <p>{@code walkFileTree}를 쓴다 — {@code Files.walk}는 지연 스트림이라 순회 중 I/O 실패가
     * {@code UncheckedIOException}으로 튀어나와 회수 전체가 매번 중단된다(읽을 수 없는 샤드 하나가 리퍼를 영구 무력화).
     * 방문 실패는 기록하고 {@code CONTINUE} — 한 항목이 나머지를 막지 못한다.
     *
     * <p>심링크는 따라가지 않는다(옵션 미지정). 루트 안의 심링크 디렉토리가 walk를 밖으로 데려가지 못하고,
     * 심링크 파일 자체도 {@code attrs.isRegularFile()}에서 걸러진다(링크를 지우는 게 안전하더라도,
     * 우리가 만들지 않은 것은 건드리지 않는다는 원칙이 우선이다).
     *
     * <p><b>빈 샤드 디렉토리는 지우지 않는다.</b> 깔끔해 보이지만 경합한다: {@code store()}는
     * {@code createDirectories(parent)} 직후 {@code CREATE_NEW}로 파일을 여는데, 그 사이에 빈 샤드를 지우면
     * 사용자의 업로드가 실패한다. 빈 700 디렉토리는 4KB일 뿐 보안·정합성 문제가 없다 —
     * 사용자에게 보이는 업로드 실패와 4KB를 맞바꾸는 건 나쁜 거래다. "정리"하지 말 것.
     *
     * @param scanRoot 실경로로 푼 루트. relativize·containment도 전부 이 값을 기준으로 해야 한다
     */
    List<Candidate> listOurFiles(Path scanRoot) {
        List<Candidate> found = new ArrayList<>();
        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(scanRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                // 우리 샤드가 아닌 하위 트리는 내려가지도 않는다 — 운영자의 다른 자료를 읽지조차 않는 게 맞다
                return SHARD_DIR.matcher(dir.getFileName().toString()).matches()
                    ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && isOurShardPath(scanRoot, file)) {
                    found.add(new Candidate(file, relPathOf(scanRoot, file), attrs.fileKey()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.warn("업로드 루트 항목을 읽지 못해 건너뜁니다: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    log.warn("업로드 루트 순회 중 오류, 건너뜁니다: {}", dir, exc);
                }
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(scanRoot, Set.of(), 3, visitor);   // 우리 파일은 정확히 깊이 3
        } catch (IOException e) {
            log.warn("업로드 루트 스캔 실패: {}", root, e);
        }
        return found;
    }

    /** 루트 기준 상대 경로가 정확히 {@code <2hex>/<2hex>/<32hex>}이고 샤드가 이름의 접두 4자와 일치하는가. */
    private boolean isOurShardPath(Path scanRoot, Path file) {
        Path rel = scanRoot.relativize(file);
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
    private String relPathOf(Path scanRoot, Path file) {
        Path rel = scanRoot.relativize(file);
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
     * 파일이 없던 행 중 <b>지금도 남아 있는</b> 것만 센다. 세기만 한다 — 행은 어떤 경우에도 건드리지 않는다.
     *
     * @param missingRels     stat 시점에 파일이 없던 rel_path
     * @param stillReferenced 검사 시점의 rel_path 스냅샷. 그 사이 사라진 행은 유실이 아니라 사용자의 정상 삭제다
     */
    int countMissing(Set<String> missingRels, Set<String> stillReferenced) {
        int missing = 0;
        for (String rel : missingRels) {
            if (stillReferenced.contains(rel)) {
                missing++;
            }
        }
        return missing;
    }
}
