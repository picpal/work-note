package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T5 — Origin 검증(CSRF). 검증은 ALLOWLIST보다 먼저 돌아야 로그인·가입·2FA 검증·복구까지 덮는다
 * (강제 로그인 CSRF 차단). MockMvc 기본 오리진은 http://localhost.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-origin?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=seed-admin-pw"
})
@AutoConfigureMockMvc
class OriginCsrfTest {
    private static final String SELF = "http://localhost";
    private static final String EVIL = "https://evil.domain.co.kr";

    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow("u1", salt, PasswordHasher.hash("pw-1234", salt)));
    }

    /** 브라우저가 보낸 세션 쿠키 시뮬레이션 — MockMvc의 .session()은 requestedSessionId를 채우지 않는다. */
    private static RequestPostProcessor sessionCookie() {
        return req -> { req.setRequestedSessionId("SID-TEST"); return req; };
    }

    // (a) 헤더 없음 + 쿠키 없음 → 통과 (CLI·헬스체크 등 비브라우저 클라이언트)
    @Test
    void noHeadersNoCookiePasses() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
    }

    // (b) 헤더 없음 + 세션 쿠키 → 거부 (브라우저라면 Origin이 반드시 붙는다)
    @Test
    void noHeadersWithSessionCookieIsRejected() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/logout").session(s).with(sessionCookie()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("invalid_origin"));
    }

    // (c) 동일 오리진 → 통과
    @Test
    void sameOriginPasses() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/logout").session(s).with(sessionCookie()).header("Origin", SELF))
            .andExpect(status().isNoContent());
    }

    // (d) 형제 서브도메인 등 타 오리진 → 403 (SameSite=Lax가 못 막는 경로)
    @Test
    void crossOriginIsRejected() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/logout").session(s).header("Origin", EVIL))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("invalid_origin"));
    }

    // (e) Origin: null (샌드박스 iframe·리다이렉트 세탁) → 403
    @Test
    void nullOriginIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").header("Origin", "null").contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isForbidden());
    }

    // (f) GET은 미검증 — 상태 변경이 아니므로
    @Test
    void getIsNotValidated() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/tree").session(s).with(sessionCookie()).header("Origin", EVIL))
            .andExpect(status().isOk());
    }

    // (g) ALLOWLIST 경로도 검증 대상 — 강제 로그인 CSRF 차단
    @Test
    void allowlistedLoginIsAlsoValidated() throws Exception {
        mvc.perform(post("/api/auth/login").header("Origin", EVIL).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("invalid_origin"));
        mvc.perform(post("/api/auth/2fa/verify").header("Origin", EVIL).contentType(APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isForbidden());
    }

    // (h) multipart 업로드도 동일하게 차단 (form 전송으로 CSRF 가능한 콘텐츠 타입)
    @Test
    void multipartUploadIsValidated() throws Exception {
        MockHttpSession s = login();
        mvc.perform(multipart("/api/nodes/n1/attachments")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes()))
                .session(s).header("Origin", EVIL))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("invalid_origin"));
    }

    // Origin 부재 시 Referer 폴백
    @Test
    void refererFallback() throws Exception {
        MockHttpSession s = login();
        mvc.perform(post("/api/auth/logout").session(s).with(sessionCookie())
                .header("Referer", SELF + "/app/index.html"))
            .andExpect(status().isNoContent());
        MockHttpSession s2 = login();
        mvc.perform(post("/api/auth/logout").session(s2).header("Referer", EVIL + "/attack"))
            .andExpect(status().isForbidden());
    }

    private MockHttpSession login() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(s).contentType(APPLICATION_JSON)
                .content("{\"emp\":\"10001\",\"password\":\"pw-1234\"}"))
            .andExpect(status().isOk());
        return s;
    }
}
