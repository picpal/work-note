package com.worknote.attachment;

import com.worknote.setting.SettingService;
import com.worknote.vault.VaultException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 첨부 디스크 저장/삭제 + 정책 강제. 메타는 DB, 바이너리는 worknote.upload.dir 아래. */
@Service
public class AttachmentService {
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
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw VaultException.invalid("파일 저장 실패: " + e.getMessage());
        }
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
        return root.resolve(row.relPath()).normalize();
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
