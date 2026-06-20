package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class Totp2faPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 19, 12, 0);

    @Test void nonAdmin_neverEnforced() {
        assertThat(Totp2faPolicy.enforced(false, false)).isFalse();
    }
    @Test void admin_withoutTotp_enforced() {
        assertThat(Totp2faPolicy.enforced(true, false)).isTrue();
    }
    @Test void admin_withTotp_notEnforced() {
        assertThat(Totp2faPolicy.enforced(true, true)).isFalse();
    }
    @Test void graceExpired_whenPastWindow() {
        assertThat(Totp2faPolicy.graceExpired(now.minusDays(8), 7, now)).isTrue();
        assertThat(Totp2faPolicy.graceExpired(now.minusDays(6), 7, now)).isFalse();
    }
    @Test void graceExpired_nullStartMeansNotExpired() {
        assertThat(Totp2faPolicy.graceExpired(null, 7, now)).isFalse();
    }
}
