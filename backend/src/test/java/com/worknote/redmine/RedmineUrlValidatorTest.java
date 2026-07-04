package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.vault.VaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedmineUrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "gopher://10.0.0.5:6379/_SET",
        "ftp://redmine.intra",
        "not a url",
        "http://",
        "http://user:pw@redmine.intra/",     // userinfo 금지
    })
    void save_rejectsMalformedOrNonHttpSchemes(String url) {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForSave(url))
            .isInstanceOf(VaultException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:6379",             // loopback
        "http://[::1]:8080",                 // IPv6 loopback
        "http://169.254.169.254/latest/meta-data/",   // link-local(메타데이터)
        "http://0.0.0.0:8080",               // any-local
    })
    void save_rejectsBlockedAddressLiterals(String url) {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForSave(url))
            .isInstanceOf(VaultException.class);
    }

    @Test void save_allowsPrivateRange_closedNetworkRedmine() {
        // 폐쇄망 인트라넷 Redmine이 정상 사용처 — 사설 대역은 허용 (계획 문서 '설계 결정 1' 참조)
        assertThatCode(() -> RedmineUrlValidator.validateForSave("http://10.0.0.5:3000"))
            .doesNotThrowAnyException();
        assertThatCode(() -> RedmineUrlValidator.validateForSave("https://192.168.1.20/redmine"))
            .doesNotThrowAnyException();
    }

    @Test void save_allowsUnresolvableHostname_fetchWillRevalidate() {
        // .invalid TLD(RFC 2606)는 결코 해석되지 않음 — set 시점엔 허용(폐쇄망 DNS 준비 전 설정 가능)
        assertThatCode(() -> RedmineUrlValidator.validateForSave("http://redmine.invalid"))
            .doesNotThrowAnyException();
    }

    @Test void fetch_rejectsUnresolvableHostname() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("http://redmine.invalid"))
            .isInstanceOf(RedmineException.Upstream.class);
    }

    @Test void fetch_rejectsBlockedAddress() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("http://169.254.169.254/"))
            .isInstanceOf(RedmineException.Upstream.class);
    }

    @Test void fetch_rejectsMalformed() {
        assertThatThrownBy(() -> RedmineUrlValidator.validateForFetch("file:///etc/passwd"))
            .isInstanceOf(RedmineException.Upstream.class);
    }

    @Test void fetch_allowsResolvableAllowedAddress() {
        // IP 리터럴은 DNS 없이 해석 — 허용 대역이면 fetch 검증도 통과
        assertThatCode(() -> RedmineUrlValidator.validateForFetch("http://10.0.0.5:3000"))
            .doesNotThrowAnyException();
    }
}
