package com.worknote.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 공유 링크 도메인 (스펙 §6). 열람은 read 권한 검사를 하지 않는다 —
 * deny를 넘는 유일한 예외가 본질이며, 통제는 만료·취소·열람수·pin·감사로 한다.
 */
@Service
public class ShareLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_DAYS = 7;   // 스펙 §6 기본 만료

    private final ShareLinkMapper mapper;
    private final NodeMapper nodes;
    private final ObjectMapper json;
    private final Clock clock;

    public ShareLinkService(ShareLinkMapper mapper, NodeMapper nodes, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.nodes = nodes;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ShareLinkRow create(String nodeId, String createdBy, Integer days, Integer maxViews,
                               List<String> pinEmps) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드를 찾을 수 없습니다: " + nodeId);
        }
        if (!"note".equals(node.type())) {
            throw VaultException.invalid("노트만 공유할 수 있습니다 (스펙 §6 — 노트 1개 read 캡)");
        }
        int d = days != null ? days : DEFAULT_DAYS;
        if (d < 1 || d > 365) {
            throw VaultException.invalid("만료 일수는 1~365 사이여야 합니다: " + d);
        }
        if (maxViews != null && maxViews < 1) {
            throw VaultException.invalid("최대 열람수는 1 이상이어야 합니다: " + maxViews);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        ShareLinkRow row = new ShareLinkRow(UUID.randomUUID().toString(), token, nodeId, createdBy,
            iso(now), iso(now.plusDays(d)), maxViews, 0, toJson(pinEmps), null);
        mapper.insert(row);
        return row;
    }

    /** 열람 — 무효 사유는 전부 404 단일화(존재·사유 비노출, 결정 S2). viewer=null은 local 모드(pin 생략, 결정 S5). */
    @Transactional
    public ShareView resolve(String token, String viewerEmp) {
        ShareLinkRow row = mapper.findByToken(token);
        if (row == null || row.revokedAt() != null
            || row.expiresAt().compareTo(iso(LocalDateTime.now(clock))) <= 0
            || (row.maxViews() != null && row.viewCount() >= row.maxViews())
            || (row.pinEmps() != null && viewerEmp != null && !fromJson(row.pinEmps()).contains(viewerEmp))) {
            throw invalidLink();
        }
        NodeRow node = nodes.findById(row.nodeId());
        if (node == null || node.deletedAt() != null) {   // 휴지통 = suspend (결정 S3)
            throw invalidLink();
        }
        mapper.incrementViewCount(row.id());
        return new ShareView(row.id(), row.nodeId(), node.name(), node.content(),
            node.updatedAt() == null ? null : node.updatedAt().substring(0, 10));
    }

    /** @return 취소된 행(감사 target 구성용). privileged = 관리자 또는 local 모드. */
    @Transactional
    public ShareLinkRow revoke(String id, String byEmp, boolean privileged) {
        ShareLinkRow row = mapper.findById(id);
        if (row == null) {
            throw VaultException.notFound("공유 링크를 찾을 수 없습니다: " + id);
        }
        // 소유권 검사를 conflict보다 먼저 — 타인에게 취소 여부(409/403 구분)를 비노출 (requireRestore와 동일 원칙)
        if (!privileged && !row.createdBy().equals(byEmp)) {
            throw VaultException.forbidden("취소 권한이 없습니다: " + id);
        }
        if (row.revokedAt() != null) {
            throw VaultException.conflict("이미 취소된 링크입니다: " + id);
        }
        mapper.revoke(id, iso(LocalDateTime.now(clock)));
        return row;
    }

    /** byEmp=null이면 전체(관리자/local), 아니면 본인 생성분만. 활성만 반환. */
    @Transactional(readOnly = true)
    public List<ShareLinkRow> listForNode(String nodeId, String byEmp) {
        List<ShareLinkRow> rows = mapper.findActiveByNode(nodeId, iso(LocalDateTime.now(clock)));
        return byEmp == null ? rows : rows.stream().filter(r -> byEmp.equals(r.createdBy())).toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveShareRow> listActive() {
        return mapper.findAllActive(iso(LocalDateTime.now(clock)));
    }

    public List<String> parsePins(String pinEmps) {
        return pinEmps == null ? null : fromJson(pinEmps);
    }

    // ---- internal ----

    private static VaultException invalidLink() {
        return VaultException.notFound("공유 링크가 유효하지 않습니다");
    }

    private String iso(LocalDateTime t) {
        return t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String toJson(List<String> pins) {
        if (pins == null) return null;
        List<String> cleaned = pins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (cleaned.isEmpty()) return null;
        try {
            return json.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw VaultException.invalid("pin 목록을 처리할 수 없습니다");
        }
    }

    private List<String> fromJson(String pins) {
        try {
            return json.readValue(pins, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            // 저장 시 우리가 직렬화한 값 — 파싱 실패는 데이터 손상. fail-closed로 빈 목록(아무도 못 염)
            return List.of();
        }
    }
}
