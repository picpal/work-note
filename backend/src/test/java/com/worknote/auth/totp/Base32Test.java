package com.worknote.auth.totp;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class Base32Test {
    // RFC 4648 §10 test vectors (no padding)
    @Test void encodesRfcVectors() {
        assertThat(Base32.encode("".getBytes(StandardCharsets.US_ASCII))).isEqualTo("");
        assertThat(Base32.encode("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Base32.encode("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Base32.encode("foo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6");
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }
    @Test void decodesRoundTrip() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
    }
    @Test void decodeIgnoresCaseAndPadding() {
        assertThat(Base32.decode("mzxw6ytboi")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("MZXW6===")).isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
    }
}
