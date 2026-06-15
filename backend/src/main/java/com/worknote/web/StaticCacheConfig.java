package com.worknote.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 정적 SPA 응답에 캐시 헤더를 박는 필터 등록 — 모든 경로 통과, 분기는 StaticCacheFilter가 담당. */
@Configuration
public class StaticCacheConfig {

    @Bean
    public FilterRegistrationBean<StaticCacheFilter> staticCacheFilter() {
        FilterRegistrationBean<StaticCacheFilter> reg = new FilterRegistrationBean<>(new StaticCacheFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(1);   // AuthFilter(order 0) 이후 — /api/*는 캐시 헤더 미설정이라 순서 무관
        return reg;
    }
}
