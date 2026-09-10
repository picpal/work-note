package com.worknote.attachment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.worknote.vault.VaultException;
import java.util.List;
import org.junit.jupiter.api.Test;

class UploadPolicyTest {
    private UploadPolicy policy() {
        return UploadPolicy.of(List.of("png", "jpg", "pdf"), 1000);
    }

    @Test
    void extExtraction_lowercases_and_takesLast() {
        assertThat(UploadPolicy.ext("Photo.PNG")).isEqualTo("png");
        assertThat(UploadPolicy.ext("a.tar.gz")).isEqualTo("gz");
    }

    @Test
    void noExtension_isRejected() {
        assertThatThrownBy(() -> policy().check("README", 10)).isInstanceOf(VaultException.class);
    }

    @Test
    void allowedExt_passes() {
        assertThatCode(() -> policy().check("a.png", 10)).doesNotThrowAnyException();
        assertThatCode(() -> policy().check("a.JPG", 10)).doesNotThrowAnyException(); // 대소문자 무관
    }

    @Test
    void disallowedExt_throws() {
        assertThatThrownBy(() -> policy().check("a.exe", 10)).isInstanceOf(VaultException.class);
    }

    @Test
    void overSize_throws() {
        assertThatThrownBy(() -> policy().check("a.png", 1001)).isInstanceOf(VaultException.class);
    }

    @Test
    void emptyFile_throws() {
        assertThatThrownBy(() -> policy().check("a.png", 0)).isInstanceOf(VaultException.class);
    }

    @Test
    void isImage_onlyKnownImageExts() {
        assertThat(UploadPolicy.isImage("png")).isTrue();
        assertThat(UploadPolicy.isImage("pdf")).isFalse();
    }

    // ─── 정책 저장 검증 (D-8: 조용한 오설정 차단) ────────────────────────────

    @Test
    void validateExts_normalizesDotAndCase() {
        assertThat(UploadPolicy.validateExts(List.of(".PNG", " Jpg ", "pdf")))
            .containsExactly("png", "jpg", "pdf");
    }

    @Test
    void validateExts_dropsDuplicatesKeepingOrder() {
        assertThat(UploadPolicy.validateExts(List.of("png", ".PNG", "pdf"))).containsExactly("png", "pdf");
    }

    @Test
    void validateExts_rejectsBadCharacters_withReason() {
        // QA 재현: ".BAD!" 입력이 "bad!"로 저장돼 어떤 파일과도 매치되지 않던 값
        assertThatThrownBy(() -> UploadPolicy.validateExts(List.of("png", ".BAD!")))
            .isInstanceOf(VaultException.class)
            .hasMessageContaining(".BAD!")
            .hasMessageContaining("영문 소문자·숫자");
    }

    @Test
    void validateExts_rejectsEmptyAndWhitespaceAndDotOnly() {
        for (String bad : List.of("", "   ", ".", "p ng", "png/", "한글")) {
            assertThatThrownBy(() -> UploadPolicy.validateExts(List.of(bad)), "허용되면 안 됨: " + bad)
                .isInstanceOf(VaultException.class);
        }
    }

    @Test
    void validateExts_rejectsTooLong() {
        assertThatThrownBy(() -> UploadPolicy.validateExts(List.of("a".repeat(17))))
            .isInstanceOf(VaultException.class);
        assertThatCode(() -> UploadPolicy.validateExts(List.of("a".repeat(16)))).doesNotThrowAnyException();
    }

    @Test
    void validateExts_rejectsEmptyList() {
        assertThatThrownBy(() -> UploadPolicy.validateExts(List.of())).isInstanceOf(VaultException.class);
    }

    @Test
    void validateMaxBytes_rangeIsOneToServletLimit() {
        assertThatCode(() -> UploadPolicy.validateMaxBytes(1)).doesNotThrowAnyException();
        assertThatCode(() -> UploadPolicy.validateMaxBytes(UploadPolicy.MAX_BYTES_LIMIT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadPolicy.validateMaxBytes(0)).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> UploadPolicy.validateMaxBytes(-1)).isInstanceOf(VaultException.class);
        // 99999MB(≈97GB) 오타 — 서블릿 상한(64MB) 위는 정책 숫자만 커지는 오설정
        assertThatThrownBy(() -> UploadPolicy.validateMaxBytes(99999L * 1024 * 1024))
            .isInstanceOf(VaultException.class)
            .hasMessageContaining("64MB");
    }

    @Test
    void readPath_staysLenient_soLegacyValuesDoNotBreakBoot() {
        // 이미 저장된 "bad!" 같은 값이 있어도 읽기는 죽지 않는다(저장 경로에서만 막는다)
        assertThat(UploadPolicy.of(List.of("bad!", ".PNG", ""), 1000).allowedExt())
            .containsExactly("bad!", "png");
    }
}
