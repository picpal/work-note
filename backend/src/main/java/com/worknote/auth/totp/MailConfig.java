package com.worknote.auth.totp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MailSender 단일 빈 팩토리.
 * @ConditionalOnProperty 대신 host 공백 여부로 분기해 빈 중복 문제를 방지
 * (빈 문자열 host 시 @ConditionalOnProperty 두 조건이 동시 매칭 → 모호 → 기동 실패 함정 회피).
 */
@Configuration
public class MailConfig {
    @Bean
    public MailSender mailSender(
            @Value("${worknote.smtp.host:}") String host,
            @Value("${worknote.smtp.port:25}") int port,
            @Value("${worknote.smtp.from:worknote@corp.local}") String from,
            @Value("${worknote.smtp.user:}") String user,
            @Value("${worknote.smtp.password:}") String password,
            @Value("${worknote.smtp.starttls:false}") boolean starttls) {
        return host.isBlank()
            ? new NoopMailSender()
            : new SmtpMailSender(host, port, from, user, password, starttls);
    }
}
