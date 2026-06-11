package com.worknote.acl;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void denyStickyBeatsCloserAllow() {
        // 조상(f1)에 deny가 있으면 더 가까운 allow(n1=read)와 무관하게 deny (§5.1 — deny 아래 재허용 없음)
        assertThat(AclResolver.nearestExplicit(CHAIN, Map.of("f1", "deny", "n1", "read")))
            .isEqualTo("deny");
    }

    @Test
    void emptyChainYieldsNull() {
        assertThat(AclResolver.nearestExplicit(List.of(), Map.of("f1", "read"))).isNull();
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
    void combineRejectsUnknownGrant() {
        // DB CHECK가 소문자 3종을 보장 — 도달하면 버그이므로 loud failure (조용한 무시는 fail-open)
        assertThatThrownBy(() -> AclResolver.combine(List.of("DENY")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DENY");
    }

    @Test
    void accessHelpersSealComparison() {
        // ordinal 비교 금지 — 의미 헬퍼로 봉인 (DENY가 최대 ordinal이라 비교가 위험)
        assertThat(Access.NONE.allowsRead()).isFalse();
        assertThat(Access.NONE.allowsEdit()).isFalse();
        assertThat(Access.READ.allowsRead()).isTrue();
        assertThat(Access.READ.allowsEdit()).isFalse();
        assertThat(Access.EDIT.allowsRead()).isTrue();
        assertThat(Access.EDIT.allowsEdit()).isTrue();
        assertThat(Access.DENY.allowsRead()).isFalse();
        assertThat(Access.DENY.allowsEdit()).isFalse();
    }

    @Test
    void publicCloserThanExcludeWins() {
        // exclude(f1)보다 public(f2)이 더 가까움 → public
        assertThat(AclResolver.publicRead(CHAIN, Map.of("f2", "public", "f1", "exclude"))).isTrue();
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
