package com.worknote.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class SmtpMailSenderTest {

    @Test void starttlsEnabled_forcesRequiredAndServerIdentityCheck() {
        Properties p = SmtpMailSender.mailProperties("smtp.corp.local", 587, true, true);
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.starttls.required")).isEqualTo("true");   // 평문 다운그레이드 차단
        assertThat(p.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("true");
    }

    @Test void starttlsDisabled_noTlsProps() {
        Properties p = SmtpMailSender.mailProperties("smtp.corp.local", 25, false, false);
        assertThat(p.getProperty("mail.smtp.starttls.enable")).isNull();
        assertThat(p.getProperty("mail.smtp.starttls.required")).isNull();
        assertThat(p.getProperty("mail.smtp.auth")).isEqualTo("false");
    }
}
