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
}
