package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** worknote.mode=server에서만 /api/*에 AuthFilter 등록 — local 모드는 1단계 동작 그대로. */
@Configuration
public class AuthFilterConfig {

    @Bean
    @ConditionalOnProperty(name = "worknote.mode", havingValue = "server")
    public FilterRegistrationBean<AuthFilter> authFilter(UserMapper users, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>(new AuthFilter(users, json));
        reg.addUrlPatterns("/api/*");
        return reg;
    }
}
