package com.worknote.redmine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.redmine.RedmineDtos.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RedmineClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public RedmineClient(ObjectMapper json) { this.json = json; }

    public String fetchCurrentLogin(String base, String token) {
        return RedmineJson.parseCurrentLogin(get(base, token, "/users/current.json"));
    }

    public List<RedmineIssueSummary> listIssues(String base, String token, RedmineQuery q) {
        StringBuilder p = new StringBuilder("/issues.json?status_id=")
            .append(q.statusId() == null || q.statusId().isBlank() ? "open" : enc(q.statusId()))
            .append("&offset=").append(Math.max(0, q.offset()))
            .append("&limit=").append(q.limit() <= 0 ? 25 : Math.min(100, q.limit()));
        if (q.assignedToMe()) p.append("&assigned_to_id=me");
        if (q.projectId() != null && !q.projectId().isBlank()) p.append("&project_id=").append(enc(q.projectId()));
        if (q.query() != null && !q.query().isBlank()) p.append("&subject=~").append(enc(q.query()));
        return RedmineJson.parseIssueList(get(base, token, p.toString()));
    }

    public RedmineIssueDetail getIssue(String base, String token, long id) {
        return RedmineJson.parseIssueDetail(get(base, token, "/issues/" + id + ".json?include=journals"));
    }

    /** 응답 본문 상한 — SSRF·오설정 시 대용량 바디로 인한 힙 고갈 방지 (감사 §4 Info). */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private JsonNode get(String base, String token, String path) {
        if (base == null || base.isBlank()) throw new RedmineException.Upstream("base_url 미설정");
        RedmineUrlValidator.validateForFetch(base);   // 호출 시점 재검증 — 저장 후 DNS 변경(리바인딩)·직접 DB 조작 대응
        String url = base.replaceAll("/+$", "") + path;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-Redmine-API-Key", token)
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<java.io.InputStream> res =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (java.io.InputStream in = res.body()) {
                body = readCapped(in);
            }
            int sc = res.statusCode();
            if (sc == 401 || sc == 403) throw new RedmineException.Auth("redmine_token_invalid");
            if (sc == 404) throw new RedmineException.NotFound("redmine_not_found");
            if (sc >= 400) throw new RedmineException.Upstream("redmine_upstream_" + sc);
            return json.readTree(body);
        } catch (RedmineException e) {
            throw e;
        } catch (Exception e) {
            throw new RedmineException.Upstream("redmine_io: " + e.getClass().getSimpleName());
        }
    }

    /** 상한 초과 시 즉시 중단 — 전체를 힙에 올리기 전에 차단. */
    static byte[] readCapped(java.io.InputStream in) throws java.io.IOException {
        byte[] buf = in.readNBytes(MAX_BODY_BYTES + 1);
        if (buf.length > MAX_BODY_BYTES) {
            throw new RedmineException.Upstream("redmine_response_too_large");
        }
        return buf;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
