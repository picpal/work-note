package com.worknote.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SPA 정적 캐시 정책: 해시 에셋=immutable, HTML 엔트리=no-cache, API=무간섭. */
class StaticCacheFilterTest {

    private final StaticCacheFilter filter = new StaticCacheFilter();

    private String cacheHeaderFor(String uri) throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getHeader("Cache-Control");
    }

    @Test void hashed_asset_is_immutable() throws Exception {
        assertThat(cacheHeaderFor("/assets/main-BQXz2MNE.js"))
            .isEqualTo("public, max-age=31536000, immutable");
    }

    @Test void index_html_is_no_cache() throws Exception {
        assertThat(cacheHeaderFor("/index.html")).isEqualTo("no-cache, must-revalidate");
    }

    @Test void root_welcome_is_no_cache() throws Exception {
        assertThat(cacheHeaderFor("/")).isEqualTo("no-cache, must-revalidate");
    }

    @Test void admin_html_is_no_cache() throws Exception {
        assertThat(cacheHeaderFor("/admin.html")).isEqualTo("no-cache, must-revalidate");
    }

    @Test void api_is_untouched() throws Exception {
        assertThat(cacheHeaderFor("/api/auth/me")).isNull();
    }
}
