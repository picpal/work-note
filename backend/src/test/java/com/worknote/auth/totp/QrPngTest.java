package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QrPngTest {
    @Test void producesPngBytes() {
        byte[] png = QrPng.encode("otpauth://totp/work-note:10001?secret=ABC&issuer=work-note", 200);
        // PNG 매직 넘버 \x89PNG
        assertThat(png.length).isGreaterThan(8);
        assertThat(png[0] & 0xff).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
    }
}
