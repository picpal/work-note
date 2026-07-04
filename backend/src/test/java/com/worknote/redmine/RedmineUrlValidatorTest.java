package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.worknote.vault.VaultException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedmineUrlValidatorTest {

    @Test void assertAllAllowed_rejectsWhenAnyResolvedAddressBlocked() throws Exception {
        // 다중 A레코드 중 하나라도 차단 대역이면 거부 — DNS 리바인딩 표면 축소
        InetAddress[] mixed = {
            InetAddress.getByName("10.0.0.5"),      // 허용(사설)
            InetAddress.getByName("127.0.0.1"),     // loopback — 차단
        };
        assertThatThrownBy(() -> RedmineUrlValidator.assertAllAllowed(mixed))
            .isInstanceOf(VaultException.class);
    }

    @Test void assertAllAllowed_allowsWhenEveryResolvedAddressAllowed() throws Exception {
        InetAddress[] ok = {
            InetAddress.getByName("10.0.0.5"),
            InetAddress.getByName("192.168.1.20"),
        };
        assertThatCode(() -> RedmineUrlValidator.assertAllAllowed(ok))
            .doesNotThrowAnyException();
    }

    @Test void assertAllAllowed_rejectsEmptyResolution_failClosed() {
        // 해석 결과 없음(null/빈 배열)은 통과가 아니라 거부 — 빈 순회의 암묵적 allow 차단
        assertThatThrownBy(() -> RedmineUrlValidator.assertAllAllowed(new InetAddress[0]))
            .isInstanceOf(VaultException.class);
    }

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
