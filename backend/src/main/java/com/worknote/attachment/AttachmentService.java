package com.worknote.attachment;

import com.worknote.setting.SettingService;
import com.worknote.vault.VaultException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 첨부 디스크 저장/삭제 + 정책 강제. 메타는 DB, 바이너리는 worknote.upload.dir 아래. */
@Service
public class AttachmentService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentMapper mapper;
    private final SettingService settings;
    private final Clock clock;
    private final Path root;

    public AttachmentService(AttachmentMapper mapper, SettingService settings, Clock clock,
                             @Value("${worknote.upload.dir:./attachments}") String uploadDir) {
        this.mapper = mapper;
        this.settings = settings;
        this.clock = clock;
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 바이트 적재 전 선검사 — multipart가 선언한 크기로 정책 위반을 조기 차단(힙 DoS 방지, 감사 §4 Low).
     * store()의 실바이트 검사와 이중 방어.
     */
    public void precheck(String filename, long size) {
        settings.uploadPolicy().check(filename, size);
    }

    @Transactional
    public AttachmentRow store(String nodeId, String filename, byte[] bytes, String createdBy) {
        settings.uploadPolicy().check(filename, bytes.length);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String relPath = uuid.substring(0, 2) + "/" + uuid.substring(2, 4) + "/" + uuid;
        Path target = root.resolve(relPath).normalize();
        if (!target.startsWith(root)) {
            throw VaultException.invalid("잘못된 저장 경로"); // 방어
        }
        try {
            writeOwnerOnly(target, bytes);
        } catch (IOException e) {
            // 부분 기록된 파일을 남기지 않는다 — DB 행이 없어 purge(deleteForNodes)가 영영 회수하지 못하고
            // 반복 실패 시 디스크에 무한 누적된다. 정리 실패가 원래 원인을 덮지 않도록 suppressed로만 붙인다.
            try {
                Files.deleteIfExists(target);
            } catch (IOException | RuntimeException cleanupFailed) {
                e.addSuppressed(cleanupFailed);
            }
            // 경로·권한 등 내부 정보가 응답에 새지 않도록 상세는 서버 로그로만, 클라엔 일반 메시지.
            log.warn("첨부 저장 실패 nodeId={} rel={}", nodeId, relPath, e);
            throw VaultException.invalid("파일을 저장하지 못했습니다");
        }
        deleteIfRolledBack(target);
        String ext = UploadPolicy.ext(filename);
        String mime = guessMime(ext);
        AttachmentRow row = new AttachmentRow("att-" + uuid, nodeId, filename, ext, mime,
            bytes.length, relPath, createdBy,
            LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        mapper.insert(row);
        return row;
    }

    public AttachmentRow findById(String id) {
        return mapper.findById(id);
    }

    public List<AttachmentRow> findByNode(String nodeId) {
        return mapper.findByNode(nodeId);
    }

    public Path pathOf(AttachmentRow row) {
        Path p = root.resolve(row.relPath()).normalize();
        if (!p.startsWith(root)) {
            // DB 손상·조작 시 루트 밖 파일 접근 차단 — store()의 쓰기 가드와 대칭 (감사 §4 Low)
            throw VaultException.invalid("잘못된 첨부 경로");
        }
        return p;
    }

    public byte[] read(AttachmentRow row) {
        try {
            return Files.readAllBytes(pathOf(row));
        } catch (IOException e) {
            throw VaultException.notFound("첨부 파일을 찾을 수 없습니다");
        }
    }

    @Transactional
    public void delete(String id) {
        AttachmentRow row = mapper.findById(id);
        if (row == null) {
            return;
        }
        mapper.delete(id);
        try {
            Files.deleteIfExists(pathOf(row));
        } catch (IOException ignored) {
            // 메타는 삭제됨 — 잔여 파일은 다음 정리로 충분
        }
    }

    /** 노트 purge 연계 — 해당 노드들의 첨부 파일+메타 일괄 삭제. */
    @Transactional
    public void deleteForNodes(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }
        for (AttachmentRow row : mapper.findByNodeIds(nodeIds)) {
            try {
                Files.deleteIfExists(pathOf(row));
            } catch (IOException ignored) {
                // 파일이 없어도 메타 삭제는 진행
            }
        }
        mapper.deleteByNodeIds(nodeIds);
    }

    /**
     * 커밋되지 못한 트랜잭션이 남긴 파일을 지운다.
     *
     * <p>파일은 트랜잭션 안에서 쓰이고 DB 행은 그 뒤에 들어간다. insert 실패·제약 위반·호출부 롤백 중
     * 무엇이든 트랜잭션이 뒤집히면 행은 사라지지만 파일은 남는데, purge({@code deleteForNodes})는
     * DB 행 기준이라 그 파일을 영영 회수하지 못한다.
     *
     * <p>{@code STATUS_ROLLED_BACK}만 지운다. {@code STATUS_UNKNOWN}(휴리스틱 종료)은 커밋됐을 수도 있어,
     * 지웠다가 행만 남기는 쪽이 파일이 남는 쪽보다 나쁘다 — 확실할 때만 지운다.
     */
    private void deleteIfRolledBack(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;   // 트랜잭션 밖 호출 — 뒤집힐 것이 없으니 등록할 것도 없다
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    Files.deleteIfExists(target);
                } catch (IOException | RuntimeException e) {
                    // 콜백에서 예외를 던지면 다른 동기화 콜백까지 말린다 — 로그만 남기고 삼킨다.
                    log.warn("롤백 후 첨부 파일 정리 실패 path={}", target, e);
                }
            }
        });
    }

    /**
     * 첨부는 <b>생성 시점에</b> 600, 샤딩 디렉토리는 700. 넓게 만들고 나중에 chmod 하면 그 사이에 창이 열리고,
     * 디렉토리가 열려 있으면 파일 권한만 조여도 목록·경로 추측이 남는다 (감사 M-6).
     * POSIX 미지원 파일시스템에선 속성 없이 생성 — 권한 강화 실패로 업로드를 막지는 않는다.
     */
    void writeOwnerOnly(Path target, byte[] bytes) throws IOException {
        boolean posix = target.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (!posix) {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            return;
        }
        Files.createDirectories(target.getParent(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try (SeekableByteChannel ch = Files.newByteChannel(target,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            writeFully(ch, bytes);
        }
    }

    /**
     * 버퍼가 빌 때까지 반복 — {@code write}는 계약상 버퍼 일부만 쓰고 그 개수를 돌려줄 수 있다.
     * 한 번만 호출하면 첨부가 잘린 채 저장되고 DB의 size와 디스크가 어긋난다(무음 손상).
     * (앞서 쓰던 {@code Files.write}는 내부에서 이 루프를 돌아준다 — 채널로 바꾸며 잃은 보장을 복원)
     */
    static void writeFully(WritableByteChannel ch, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static String guessMime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "txt", "md", "csv" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
}
