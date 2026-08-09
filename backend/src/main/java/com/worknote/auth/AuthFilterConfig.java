package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.auth.totp.TotpService;
import com.worknote.setting.SettingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** worknote.mode=server에서만 /api/*에 AuthFilter 등록 — local 모드는 1단계 동작 그대로. */
@Configuration
public class AuthFilterConfig {

    @Bean
    @ConditionalOnProperty(name = "worknote.mode", havingValue = "server")
    public FilterRegistrationBean<AuthFilter> authFilter(
            UserMapper users, ObjectMapper json,
            TotpService totpService, RoleCaps roleCaps,
            SettingService settings, Clock clock,
            // 비우면 요청에서 오리진을 유도 — 프록시가 TLS를 종단해 요청 스킴이 실제 접속 오리진과 다를 때만 지정
            @Value("${worknote.canonical-origin:}") String canonicalOrigin) {
        FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>(
            new AuthFilter(users, json, totpService, roleCaps, settings, clock,
                new OriginValidator(canonicalOrigin)));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(0);   // 인증 필터 순서 명시 — 후속 필터 추가 시 암묵 order 의존 방지
        return reg;
    }
}
