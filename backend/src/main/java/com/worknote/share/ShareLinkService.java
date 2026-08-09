package com.worknote.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ShareViewSession viewed;

    public ShareLinkService(ShareLinkMapper mapper, NodeMapper nodes, ObjectMapper json, Clock clock,
                            ShareViewSession viewed) {
        this.mapper = mapper;
        this.nodes = nodes;
        this.json = json;
        this.clock = clock;
        this.viewed = viewed;
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

    /**
     * 검증 대상 — 열람 소비(VIEW)와 이미 소비한 열람의 콘텐츠 접근(CONTENT)을 나눈다.
     * 열람수 상한만 다르게 보고 나머지(취소·만료·pin·휴지통)는 동일하다.
     */
    private enum Use { VIEW, CONTENT }

    /**
     * 공통 검증 — 무효 사유 전부 404 단일화(존재·사유 비노출, 결정 S2).
     * 활성·미만료·열람수·pin·노드 존재 확인. viewer=null은 local 모드(pin 생략, 결정 S5).
     * @return 검증을 통과한 행 + 노드 (열람수는 증가시키지 않음 — 증가는 resolve 책임).
     */
    private ValidShare validate(String token, String viewerEmp, Use use) {
        ShareLinkRow row = mapper.findByToken(token);
        if (row == null || row.revokedAt() != null
            || row.expiresAt().compareTo(iso(LocalDateTime.now(clock))) <= 0
            || (row.pinEmps() != null && viewerEmp != null && !fromJson(row.pinEmps()).contains(viewerEmp))) {
            throw invalidLink();
        }
        // 상한 소진 후에도 CONTENT는 이 세션의 이 계정이 소비한 열람에 한해 통과 — 첨부는 열람수를
        // 소모하지 않으므로(이미지 N개 = 열람 1회) 막으면 마지막 열람의 이미지가 전부 깨진다.
        // 표식은 resolve만 남기므로 본문 재열람·타 세션·교대 로그인한 타 계정은 그대로 거부된다.
        if (row.maxViews() != null && row.viewCount() >= row.maxViews()
            && (use == Use.VIEW || !viewed.hasViewed(row.id(), viewerEmp))) {
            throw invalidLink();
        }
        NodeRow node = nodes.findById(row.nodeId());
        if (node == null || node.deletedAt() != null) {   // 휴지통 = suspend (결정 S3)
            throw invalidLink();
        }
        return new ValidShare(row, node);
    }

    /** 열람 — 검증 통과 시 열람수 증가 + 노트 내용 반환. 상태를 바꾸므로 호출부는 POST다. */
    @Transactional
    public ShareView resolve(String token, String viewerEmp) {
        ValidShare v = validate(token, viewerEmp, Use.VIEW);
        NodeRow node = v.node();
        mapper.incrementViewCount(v.link().id());
        // 이 세션의 이 계정이 소비한 열람 — 첨부 서빙의 근거 (TTL 만료까지)
        markViewedAfterCommit(v.link().id(), viewerEmp);
        return new ShareView(v.link().id(), v.link().nodeId(), node.name(), node.content(),
            node.updatedAt() == null ? null : node.updatedAt().substring(0, 10));
    }

    /** 첨부 이미지 서빙용 — 검증만, 열람수 미증가. 노드 id 반환. */
    @Transactional(readOnly = true)
    public String nodeIdForAttachment(String token, String viewerEmp) {
        return validate(token, viewerEmp, Use.CONTENT).link().nodeId();
    }

    /** validate 내부 결과 — 검증된 링크 행 + 노드. */
    private record ValidShare(ShareLinkRow link, NodeRow node) {}

    /**
     * 소비한 열람의 표식을 <b>커밋된 뒤에</b> 남긴다.
     *
     * <p>표식은 HttpSession에 있어 트랜잭션에 참여하지 않는다. 열람수 증가와 나란히 남기면 커밋 실패·
     * 호출부 롤백 때 view_count는 되돌아가는데 표식만 살아남는다 — 열람을 소비하지도, 본문을 받지도
     * 못한 세션이 소진된 링크의 첨부를 계속 가져가는 자격이 된다(롤백을 넘어 살아남는 권한 부여).
     *
     * <p>{@code AttachmentService.deleteIfRolledBack}과 가드의 방향이 <b>반대</b>다. 저쪽은 동기화가
     * 없으면 뒤집힐 트랜잭션도 없으니 그냥 빠져나가지만, 여기서 빠져나가면 표식이 조용히 사라져
     * 정당한 열람자의 이미지가 깨진다 — 되돌아갈 것이 없다면 지금 남기는 것이 맞다. 합치지 말 것.
     */
    private void markViewedAfterCommit(String linkId, String viewerEmp) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            viewed.markViewed(linkId, viewerEmp);   // 트랜잭션 밖 호출 — 되돌아갈 것이 없다
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 요청 스레드가 아직 바인딩된 채로 실행된다 — markViewed의 RequestContextHolder가 그대로 해석된다
                viewed.markViewed(linkId, viewerEmp);
            }
        });
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
