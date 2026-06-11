package com.worknote.acl;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AclResolverTest {

    // chain은 항상 자신→루트 순
    private static final List<String> CHAIN = List.of("n1", "f2", "f1");

    @Test
    void nearestExplicitPicksClosestEntry() {
        // f1=read, f2=edit → n1 기준 가장 가까운 명시는 f2의 edit
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "read", "f2", "edit")))
            .isEqualTo("edit");
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "read"))).isEqualTo("read");
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of())).isNull();
    }

    @Test
    void carveOutDenyOnNoteBeatsAncestorEdit() {
        // 폴더 edit + 노트 deny → 노트에서 deny (한 주체 안 nearest-explicit)
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "edit", "n1", "deny")))
            .isEqualTo("deny");
    }

    @Test
    void combineDenyWinsOverAnyAllow() {
        // 다중 주체: 개인 edit + 팀 deny → 차단 (deny-우선 합집합)
        assertThat(AclResolver.combine(Arrays.asList("edit", "deny"))).isEqualTo(Access.DENY);
        assertThat(AclResolver.combine(Arrays.asList("deny", null))).isEqualTo(Access.DENY);
    }

    @Test
    void combineUnionsAllowsMostGenerous() {
        assertThat(AclResolver.combine(Arrays.asList("read", "edit"))).isEqualTo(Access.EDIT);
        assertThat(AclResolver.combine(Arrays.asList("read", null))).isEqualTo(Access.READ);
        assertThat(AclResolver.combine(Arrays.asList((String) null, null))).isEqualTo(Access.NONE);
        assertThat(AclResolver.combine(List.of())).isEqualTo(Access.NONE);
    }

    @Test
    void publicReadNearestFlagWins() {
        // f1=public → n1 public 상속
        assertThat(AclResolver.publicRead(CHAIN, Map.of("f1", "public"))).isTrue();
        // n1=exclude가 더 가까움 → 차단 (새 노트 기본 제외)
        assertThat(AclResolver.publicRead(CHAIN, Map.of("f1", "public", "n1", "exclude"))).isFalse();
        // 플래그 없음 → default-deny
        assertThat(AclResolver.publicRead(CHAIN, Map.of())).isFalse();
    }
}
