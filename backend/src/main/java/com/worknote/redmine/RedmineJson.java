package com.worknote.redmine;

import com.fasterxml.jackson.databind.JsonNode;
import com.worknote.redmine.RedmineDtos.*;
import java.util.ArrayList;
import java.util.List;

final class RedmineJson {
    private RedmineJson() {}

    static String parseCurrentLogin(JsonNode root) {
        return text(root.path("user"), "login");
    }

    static List<RedmineIssueSummary> parseIssueList(JsonNode root) {
        List<RedmineIssueSummary> out = new ArrayList<>();
        for (JsonNode n : root.path("issues")) {
            out.add(new RedmineIssueSummary(
                n.path("id").asLong(),
                text(n, "subject"),
                text(n.path("status"), "name"),
                nodeName(n, "assigned_to"),
                text(n.path("project"), "name"),
                text(n, "updated_on")));
        }
        return out;
    }

    static RedmineIssueDetail parseIssueDetail(JsonNode root) {
        JsonNode n = root.path("issue");
        List<RedmineComment> comments = new ArrayList<>();
        for (JsonNode j : n.path("journals")) {
            String notes = text(j, "notes");
            if (notes == null || notes.isBlank()) continue;   // 상태변경만 있는 journal 제외
            comments.add(new RedmineComment(nodeName(j, "user"), text(j, "created_on"), notes));
        }
        return new RedmineIssueDetail(
            n.path("id").asLong(), text(n, "subject"), text(n, "description"),
            text(n.path("status"), "name"), nodeName(n, "assigned_to"),
            text(n.path("project"), "name"), text(n.path("priority"), "name"),
            text(n, "due_date"), text(n, "updated_on"), comments);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
    private static String nodeName(JsonNode parent, String f) {
        JsonNode v = parent.path(f);
        return v.isMissingNode() || v.isNull() ? null : text(v, "name");
    }
}
