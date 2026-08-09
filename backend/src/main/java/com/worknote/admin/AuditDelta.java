package com.worknote.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 권한 변경 델타 JSON 생성 — audit_log.detail 전용(M-1).
 *
 * <p>절단하지 않는다. 크기 기반 절단은 "큰 변경을 만들면 증거가 사라지는" 인멸 경로가 되므로,
 * 대신 입력을 제한한다(SetAclRequest.entries의 @Size). 상한 이내면 델타는 항상 온전히 저장된다.
 *
 * <p>변경 없음 = null 반환. 빈 델타 행을 남기면 감사 화면에서 "펼쳐도 아무것도 없는 토글"이 된다.
 * 키·원소는 정렬해 같은 변경이 항상 같은 JSON이 되게 한다(diff·재구성 용이).
 */
@Component
class AuditDelta {

    private final ObjectMapper json;

    AuditDelta(ObjectMapper json) {
        this.json = json;
    }

    /**
     * ACL replace-all 델타. before/after는 주체키("user:u1") → grantType.
     * added/removed/changed 중 비어 있는 갈래는 키 자체를 생략한다.
     */
    String acl(Map<String, String> before, Map<String, String> after) {
        List<Object> added = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(after).entrySet()) {
            String prev = before.get(e.getKey());
            if (prev == null) {
                added.add(ordered("p", e.getKey(), "g", e.getValue()));
            } else if (!prev.equals(e.getValue())) {
                changed.add(ordered("p", e.getKey(), "from", prev, "to", e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : new TreeMap<>(before).entrySet()) {
            if (!after.containsKey(e.getKey())) {
                removed.add(ordered("p", e.getKey(), "g", e.getValue()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!added.isEmpty()) out.put("added", added);
        if (!removed.isEmpty()) out.put("removed", removed);
        if (!changed.isEmpty()) out.put("changed", changed);
        return write(out);
    }

    /** 역할 수정 델타. 이름·caps 중 실제로 바뀐 갈래만 담는다. */
    String role(String nameFrom, String nameTo, Set<String> capsFrom, Set<String> capsTo) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (nameFrom != null && !nameFrom.equals(nameTo)) {
            out.put("name", ordered("from", nameFrom, "to", nameTo));
        }
        List<String> capsAdded = new ArrayList<>(new TreeSet<>(capsTo));
        capsAdded.removeAll(capsFrom);
        List<String> capsRemoved = new ArrayList<>(new TreeSet<>(capsFrom));
        capsRemoved.removeAll(capsTo);
        if (!capsAdded.isEmpty() || !capsRemoved.isEmpty()) {
            Map<String, Object> caps = new LinkedHashMap<>();
            caps.put("added", capsAdded);
            caps.put("removed", capsRemoved);
            out.put("caps", caps);
        }
        return write(out);
    }

    /**
     * public 노출 델타. 설정 전 모드가 없으면 from=null, 해제면 to=null.
     * 공개 노출은 되돌려도 흔적이 남아야 하므로 from==to(무변화 재설정)여도 기록한다.
     */
    String publicMode(String from, String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from);
        out.put("to", to);
        return write(out);
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private String write(Map<String, Object> out) {
        if (out.isEmpty()) return null;   // 변경 없음 — detail 컬럼도 비운다
        try {
            return json.writeValueAsString(out);
        } catch (Exception e) {
            // 감사 델타 직렬화 실패는 조용히 넘기지 않는다 — @Transactional 안이라 변경도 함께 롤백된다(T7-a)
            throw new IllegalStateException("감사 델타 직렬화 실패", e);
        }
    }
}
