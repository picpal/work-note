package com.worknote.pii;

import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** PII 상태 기계 + 예외 요청/관리자 결정. 탐지는 PiiDetector(순수)에 위임. */
@Service
public class PiiService {

    private final PiiMapper mapper;
    private final Clock clock;

    public PiiService(PiiMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
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
        String matched = PiiType.csv(PiiDetector.detect(content));
        PiiFlagRow cur = mapper.findFlag(nodeId);

        if (matched.isEmpty()) {
            if (cur != null) mapper.deleteFlag(nodeId);
            return new PiiEval("none", List.of());
        }
        if (cur == null) {
            mapper.insertFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null));
            return new PiiEval("suspected", typesList(matched));
        }
        if ("exempted".equals(cur.status())) {
            Set<String> old = new TreeSet<>(typesList(cur.types()));
            Set<String> nw = new TreeSet<>(typesList(matched));
            if (old.containsAll(nw)) {
                return new PiiEval("exempted", typesList(cur.types()));
            }
            mapper.updateFlag(new PiiFlagRow(nodeId, "suspected", matched, now(),
                null, null, null, null, null, null));
            return new PiiEval("suspected", typesList(matched));
        }
        mapper.updateFlag(new PiiFlagRow(nodeId, cur.status(), matched, now(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(),
            cur.decidedBy(), cur.decidedAt(), cur.decisionReason()));
        return new PiiEval(cur.status(), typesList(matched));
    }

    /** 사용자 예외 요청 — suspected/rejected에서만 허용. */
    @Transactional
    public void requestException(String nodeId, String emp, String reason) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null || !(cur.status().equals("suspected") || cur.status().equals("rejected"))) {
            throw VaultException.invalid("예외 요청할 수 있는 상태가 아닙니다");
        }
        mapper.updateFlag(new PiiFlagRow(nodeId, "requested", cur.types(), cur.detectedAt(),
            emp, now(), reason, null, null, null));
    }

    /** 관리자 허용 → exempted. */
    @Transactional
    public void approve(String nodeId, String adminEmp) {
        PiiFlagRow cur = requireFlag(nodeId);
        mapper.updateFlag(new PiiFlagRow(nodeId, "exempted", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), null));
    }

    /** 관리자 반려 → rejected(+사유). */
    @Transactional
    public void reject(String nodeId, String adminEmp, String reason) {
        PiiFlagRow cur = requireFlag(nodeId);
        mapper.updateFlag(new PiiFlagRow(nodeId, "rejected", cur.types(), cur.detectedAt(),
            cur.requestedBy(), cur.requestedAt(), cur.requestReason(), adminEmp, now(), reason));
    }

    private PiiFlagRow requireFlag(String nodeId) {
        PiiFlagRow cur = mapper.findFlag(nodeId);
        if (cur == null) throw VaultException.notFound("플래그가 없습니다: " + nodeId);
        return cur;
    }
}
