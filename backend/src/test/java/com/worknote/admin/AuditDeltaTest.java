package com.worknote.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 감사 델타 JSON 모양 박제 — 프런트 auditDetail.ts가 이 계약을 파싱한다. */
class AuditDeltaTest {

    private final AuditDelta delta = new AuditDelta(new ObjectMapper());

    private static Map<String, String> grants(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void acl_added_removed_changed() {
        String json = delta.acl(
            grants("user:u-1", "edit", "team:t-qa", "read"),
            grants("team:t-dev", "read", "team:t-qa", "deny"));
        assertThat(json).isEqualTo(
            "{\"added\":[{\"p\":\"team:t-dev\",\"g\":\"read\"}],"
                + "\"removed\":[{\"p\":\"user:u-1\",\"g\":\"edit\"}],"
                + "\"changed\":[{\"p\":\"team:t-qa\",\"from\":\"read\",\"to\":\"deny\"}]}");
    }

    @Test
    void acl_emptyBranchesOmitted_andNoChangeIsNull() {
        assertThat(delta.acl(grants(), grants("user:u-1", "read")))
            .isEqualTo("{\"added\":[{\"p\":\"user:u-1\",\"g\":\"read\"}]}");
        assertThat(delta.acl(grants("user:u-1", "read"), grants("user:u-1", "read"))).isNull();
        assertThat(delta.acl(grants(), grants())).isNull();
    }

    @Test
    void acl_isDeterministic_regardlessOfInputOrder() {
        String a = delta.acl(grants(), grants("user:u-2", "read", "team:t-1", "deny"));
        String b = delta.acl(grants(), grants("team:t-1", "deny", "user:u-2", "read"));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void role_nameAndCaps() {
        assertThat(delta.role("검토자", "리뷰어", Set.of("res.read"), Set.of("res.read", "res.delete")))
            .isEqualTo("{\"name\":{\"from\":\"검토자\",\"to\":\"리뷰어\"},"
                + "\"caps\":{\"added\":[\"res.delete\"],\"removed\":[]}}");
    }

    @Test
    void role_onlyChangedBranchesKept_andNoChangeIsNull() {
        assertThat(delta.role("검토자", "검토자", Set.of("res.read"), Set.of()))
            .isEqualTo("{\"caps\":{\"added\":[],\"removed\":[\"res.read\"]}}");
        assertThat(delta.role("검토자", "리뷰어", Set.of("res.read"), Set.of("res.read")))
            .isEqualTo("{\"name\":{\"from\":\"검토자\",\"to\":\"리뷰어\"}}");
        assertThat(delta.role("검토자", "검토자", Set.of("res.read"), Set.of("res.read"))).isNull();
    }

    @Test
    void publicMode_alwaysRecorded_evenWhenUnchanged() {
        assertThat(delta.publicMode(null, "public")).isEqualTo("{\"from\":null,\"to\":\"public\"}");
        assertThat(delta.publicMode("public", null)).isEqualTo("{\"from\":\"public\",\"to\":null}");
        // 공개 노출은 되돌려도 흔적이 남아야 하므로 무변화 재설정도 기록한다
        assertThat(delta.publicMode("public", "public")).isEqualTo("{\"from\":\"public\",\"to\":\"public\"}");
    }
}
