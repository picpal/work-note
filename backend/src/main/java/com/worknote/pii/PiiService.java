package com.worknote.pii;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.worknote.vault.NodeRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/** PII 상태 기계 + 예외 요청/관리자 결정. 탐지는 PiiDetector(순수)에 위임. */
@Service
public class PiiService {

    private final PiiMapper mapper;
    private final NodeMapper nodeMapper;
    private final Clock clock;

    public PiiService(PiiMapper mapper, NodeMapper nodeMapper, Clock clock) {
        this.mapper = mapper;
        this.nodeMapper = nodeMapper;
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

    private static List<String> typesList(String csv) {
        return (csv == null || csv.isEmpty()) ? List.of() : Arrays.asList(csv.split(","));
    }

    /** 저장 시 재탐지 + 상태 기계 적용. content가 변경된 PATCH에서만 호출. */
    @Transactional
    public PiiEval evaluate(String nodeId, String content) {
        PiiDetector.Scan scan = PiiDetector.scan(content);
        String matched = PiiType.csv(scan.types());
        String hash = hashSpans(scan.spans());
        PiiFlagRow cur = mapper.findFlag(nodeId);

        if (matched.isEmpty()) {
            if (cur != null) mapper.deleteFlag(nodeId);
            return new PiiEval("none", List.of());
        }
        if (cur == null) {
            mapper.insertFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null, hash, null));
            return new PiiEval("suspected", typesList(matched));
        }
        // 승인된 값으로 (다시) 들어오면 예외 재적용 — 이전 허용 해시 집합에 현재 값이 있으면.
        if (parseHashes(cur.exemptHashes()).contains(hash)) {
            mapper.updateFlag(new PiiFlagRow(nodeId, "exempted", matched, now(),
                cur.requestedBy(), cur.requestedAt(), cur.requestReason(),
                cur.decidedBy(), cur.decidedAt(), cur.decisionReason(), hash, cur.exemptHashes()));
            return new PiiEval("exempted", typesList(matched));
        }
        if ("exempted".equals(cur.status())) {
            // 예외였는데 승인되지 않은 값으로 바뀜 → 의심 복귀(승인 집합은 보존 → 되돌아오면 다시 예외).
            mapper.updateFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null, hash, cur.exemptHashes()));
            return new PiiEval("suspected", typesList(matched));
        }
        mapper.updateFlag(new PiiFlagRow(nodeId, cur.status(), matched, now(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(),
            cur.decidedBy(), cur.decidedAt(), cur.decisionReason(), hash, cur.exemptHashes()));
        return new PiiEval(cur.status(), typesList(matched));
    }

    /** 승인 해시 CSV 파싱(순서 보존·중복제거). null/빈 → 빈 집합. */
    private static LinkedHashSet<String> parseHashes(String csv) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String h : csv.split(",")) if (!h.isEmpty()) set.add(h);
        }
        return set;
    }

    /** 탐지된 원문 스팬의 SHA-256 hex(정렬·중복제거). 평문 PII는 저장하지 않고 해시만 비교. */
    private static String hashSpans(List<String> spans) {
        if (spans == null || spans.isEmpty()) return null;
        String joined = String.join("\n", new TreeSet<>(spans));
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256은 표준 보장
        }
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
        LinkedHashSet<String> exempt = parseHashes(cur.exemptHashes());
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
