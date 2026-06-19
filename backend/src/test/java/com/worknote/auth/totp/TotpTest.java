package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {
    // RFC 6238 표준 시드 "12345678901234567890" → Base32
    private static final String SECRET = Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test void rfc6238Vectors_sha1_6digits() {
        assertThat(Totp.codeAt(SECRET, 59L)).isEqualTo("287082");
        assertThat(Totp.codeAt(SECRET, 1111111109L)).isEqualTo("081804");
        assertThat(Totp.codeAt(SECRET, 1111111111L)).isEqualTo("050471");
        assertThat(Totp.codeAt(SECRET, 1234567890L)).isEqualTo("005924");
        assertThat(Totp.codeAt(SECRET, 2000000000L)).isEqualTo("279037");
    }

    @Test void verify_acceptsCurrentStep_returnsMatchedStep() {
        long epoch = 1111111109L;       // step = epoch/30
        long step = epoch / 30;
        assertThat(Totp.verify(SECRET, "081804", epoch, 0L)).isEqualTo(step);
    }

    @Test void verify_acceptsPlusMinusOneWindow() {
        long step = 1111111109L / 30;
        String prevCode = Totp.codeAt(SECRET, (step - 1) * 30);
        assertThat(Totp.verify(SECRET, prevCode, 1111111109L, 0L)).isEqualTo(step - 1);
    }

    @Test void verify_rejectsWrongCode() {
        assertThat(Totp.verify(SECRET, "000000", 1111111109L, 0L)).isEqualTo(-1L);
    }

    @Test void verify_rejectsReplay_stepNotAfterLastStep() {
        long step = 1111111109L / 30;
        // lastStep == 현재 step → 재생, 거부
        assertThat(Totp.verify(SECRET, "081804", 1111111109L, step)).isEqualTo(-1L);
    }
}
