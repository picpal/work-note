package com.worknote.pii;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.worknote.vault.NodeRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** PII 상태 기계 + 예외 요청/관리자 결정. 탐지는 PiiDetector(순수)에 위임. */
@Service
public class PiiService {

    private final PiiMapper mapper;
    private final NodeMapper nodeMapper;
    private final PiiFlagStore flags;
    private final Clock clock;

    public PiiService(PiiMapper mapper, NodeMapper nodeMapper, PiiFlagStore flags, Clock clock) {
        this.mapper = mapper;
        this.nodeMapper = nodeMapper;
        this.flags = flags;
        this.clock = clock;
    }

    /** 능동 알림 수신자 = 최종 수정자(node.updated_by). 없으면 invalid → 422. */
    @Transactional(readOnly = true)
    public String recipientForNotice(String nodeId) {
        String emp = nodeMapper.findUpdatedBy(nodeId);
        if (emp == null) throw VaultException.invalid("최종 수정자가 없어 알림을 보낼 수 없습니다");
        return emp;
    }

    private String now() {
        return LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 저장 시 재탐지 + 상태 기계 적용. content가 변경된 PATCH에서만 호출.
     *
     * <p><b>의도적으로 @Transactional이 아니다.</b> 탐지는 본문 길이에 비례하는 CPU 작업이고,
     * 커넥션 풀이 1이라 트랜잭션 안에서 스캔하면 요청 하나가 앱 전체를 멈춘다.
     * 스캔을 먼저 끝내고, DB 반영만 PiiFlagStore(별도 빈)의 트랜잭션에 맡긴다.
     */
    public PiiEval evaluate(String nodeId, String content) {
        PiiDetector.Scan scan = PiiDetector.scan(content);   // 트랜잭션 밖 (CPU)
        return flags.apply(nodeId, scan);                    // 트랜잭션 안 (DB만)
    }

    /** 사용자 예외 요청 — suspected/rejected에서만 허용. */
    @Transactional
    public void requestException(String nodeId, String emp, String reason) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null || !(cur.status().equals("suspected") || cur.status().equals("rejected"))) {
            throw VaultException.invalid("예외 요청할 수 있는 상태가 아닙니다");
        }
        mapper.updateFlag(new PiiFlagRow(nodeId, "requested", cur.types(), cur.detectedAt(),
            emp, now(), reason, null, null, null, cur.matchedHash(), cur.exemptHashes()));
    }

    /** 관리자 허용 → exempted. 현재 값 해시를 승인 집합에 누적(되돌아오면 재적용). */
    @Transactional
    public void approve(String nodeId, String adminEmp) {
        PiiFlagRow cur = requireFlag(nodeId);
        LinkedHashSet<String> exempt = PiiFlagStore.parseHashes(cur.exemptHashes());
        if (cur.matchedHash() != null) exempt.add(cur.matchedHash());
        String exemptCsv = exempt.isEmpty() ? null : String.join(",", exempt);
        mapper.updateFlag(new PiiFlagRow(nodeId, "exempted", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), null,
            cur.matchedHash(), exemptCsv));
    }

    /** 관리자 반려 → rejected(+사유). */
    @Transactional
    public void reject(String nodeId, String adminEmp, String reason) {
        PiiFlagRow cur = requireFlag(nodeId);
        mapper.updateFlag(new PiiFlagRow(nodeId, "rejected", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), reason,
            cur.matchedHash(), cur.exemptHashes()));
    }

    private PiiFlagRow requireFlag(String nodeId) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null) throw VaultException.notFound("플래그가 없습니다: " + nodeId);
        return cur;
    }

    /** 능동 알림(flagged) — recipient에게. 중복(미확인 동일 kind) 시 sent_at만 갱신. */
    @Transactional
    public void notice(String nodeId, String recipient, String adminEmp) {
        sendNotice(nodeId, recipient, "flagged", null, adminEmp);
    }

    /** 허용 + 요청자에게 approved 알림. */
    @Transactional
    public void approveWithNotice(String nodeId, String adminEmp) {
        PiiFlagRow cur = requireFlag(nodeId);
        approve(nodeId, adminEmp);
        if (cur.requestedBy() != null) sendNotice(nodeId, cur.requestedBy(), "approved", null, adminEmp);
    }

    /** 반려 + 요청자에게 rejected 알림(사유 포함). */
    @Transactional
    public void rejectWithNotice(String nodeId, String adminEmp, String reason) {
        PiiFlagRow cur = requireFlag(nodeId);
        reject(nodeId, adminEmp, reason);
        if (cur.requestedBy() != null) sendNotice(nodeId, cur.requestedBy(), "rejected", reason, adminEmp);
    }

    private void sendNotice(String nodeId, String recipient, String kind, String message, String adminEmp) {
        Long dup = mapper.findUnackedNoticeId(nodeId, recipient, kind);
        if (dup != null) { mapper.touchNotice(dup, message, now()); return; }
        mapper.insertNotice(new PiiNoticeRow(null, nodeId, recipient, kind, message, adminEmp, now(), null));
    }

    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> noticesFor(String recipient) {
        return mapper.noticesFor(recipient);
    }

    @Transactional
    public void ack(String recipient, java.util.List<Long> ids) {
        mapper.ack(recipient, ids, now());
    }

    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> adminList() { return mapper.adminList(); }

    @Transactional(readOnly = true)
    public java.util.List<java.util.Map<String, Object>> adminRequests() { return mapper.adminRequests(); }

    /** 관리자 본문 열람 — 본문 + 매치 라인. 삭제/부재 시 404. */
    @Transactional(readOnly = true)
    public PiiContentResponse noteContent(String nodeId) {
        NodeRow node = nodeMapper.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노트를 찾을 수 없습니다: " + nodeId);
        }
        String content = node.content() == null ? "" : node.content();
        return new PiiContentResponse(nodeId, node.name(), content,
            toMatchLines(content, PiiDetector.scanMatches(content)));
    }

    /** 매치 start(문자 인덱스)를 (line, col)로 변환. 본문 1회 순회로 개행 누적 — O(n+m).
     *  matches는 start 오름차순(scanMatches 보장). */
    static List<PiiContentResponse.MatchLine> toMatchLines(String content, List<PiiDetector.Match> matches) {
        List<PiiContentResponse.MatchLine> out = new ArrayList<>();
        if (matches.isEmpty()) return out;
        int line = 1, lineStart = 0, idx = 0;
        for (int i = 0; i <= content.length() && idx < matches.size(); i++) {
            while (idx < matches.size() && matches.get(idx).start() == i) {
                out.add(new PiiContentResponse.MatchLine(
                    matches.get(idx).type().name().toLowerCase(),
                    line, i - lineStart, matches.get(idx).value()));
                idx++;
            }
            if (i < content.length() && content.charAt(i) == '\n') { line++; lineStart = i + 1; }
        }
        return out;
    }
}
