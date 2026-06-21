package com.worknote.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** 보안 헤더 필터 등록 — local·server 양쪽 모두. AuthFilter(order 0)보다 먼저 실행해 401/403 응답에도 헤더 부착. */
@Configuration
public class SecurityHeadersConfig {

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg =
            new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);   // 최선두 — 모든 응답(에러·인증실패 포함)에 헤더 보장
        return reg;
    }
}
