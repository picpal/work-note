package com.worknote.pii;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * PII 플래그 상태 기계의 DB 반영 — 트랜잭션 경계 전용 빈.
 *
 * <p>PiiService에서 분리한 이유: 탐지(CPU)는 트랜잭션 밖에서 끝나야 한다.
 * hikari.maximum-pool-size가 1(SQLite 단일 라이터)이라, 트랜잭션 안에서 본문을 스캔하면
 * 그 요청 하나가 앱 전체의 유일한 커넥션을 점유해 모든 사용자가 멈춘다.
 * 같은 클래스 안에서 @Transactional 메서드를 자기호출하면 프록시를 우회해 트랜잭션이
 * 사라지므로, 경계를 실제로 분리하려면 별도 빈이어야 한다.
 */
@Component
class PiiFlagStore {

    private final PiiMapper mapper;
    private final Clock clock;

    PiiFlagStore(PiiMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 이미 끝난 스캔 결과를 상태 기계에 반영. 여기서부터가 트랜잭션 — CPU 작업 금지. */
    @Transactional
    PiiEval apply(String nodeId, PiiDetector.Scan scan) {
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

    private String now() {
        return LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static List<String> typesList(String csv) {
        return (csv == null || csv.isEmpty()) ? List.of() : Arrays.asList(csv.split(","));
    }

    /** 승인 해시 CSV 파싱(순서 보존·중복제거). null/빈 → 빈 집합. */
    static LinkedHashSet<String> parseHashes(String csv) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String h : csv.split(",")) if (!h.isEmpty()) set.add(h);
        }
        return set;
    }

    /** 탐지된 원문 스팬의 SHA-256 hex(정렬·중복제거). 평문 PII는 저장하지 않고 해시만 비교. */
    static String hashSpans(List<String> spans) {
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
}
