package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.redmine.RedmineDtos.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedmineJsonTest {
    final ObjectMapper om = new ObjectMapper();

    @Test void parseIssueList_maps_fields() throws Exception {
        String json = """
        {"issues":[
          {"id":1234,"subject":"결제 타임아웃","status":{"name":"In Progress"},
           "assigned_to":{"name":"김OO"},"project":{"name":"결제"},"updated_on":"2026-06-20T09:00:00Z"}
        ],"total_count":1}""";
        List<RedmineIssueSummary> r = RedmineJson.parseIssueList(om.readTree(json));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).id()).isEqualTo(1234);
        assertThat(r.get(0).statusName()).isEqualTo("In Progress");
        assertThat(r.get(0).assignedToName()).isEqualTo("김OO");
    }

    @Test void parseIssueList_handles_missing_assignee() throws Exception {
        String json = """
        {"issues":[{"id":1,"subject":"s","status":{"name":"New"},"project":{"name":"P"},"updated_on":"x"}]}""";
        List<RedmineIssueSummary> r = RedmineJson.parseIssueList(om.readTree(json));
        assertThat(r.get(0).assignedToName()).isNull();
    }

    @Test void parseIssueDetail_filters_empty_journals() throws Exception {
        String json = """
        {"issue":{"id":7,"subject":"버그","description":"본문","status":{"name":"New"},
          "priority":{"name":"높음"},"due_date":"2026-07-01","updated_on":"x",
          "project":{"name":"P"},"assigned_to":{"name":"홍"},
          "journals":[
            {"user":{"name":"홍"},"created_on":"2026-06-20T10:00:00Z","notes":"댓글1"},
            {"user":{"name":"이"},"created_on":"2026-06-20T11:00:00Z","notes":""}
          ]}}""";
        RedmineIssueDetail d = RedmineJson.parseIssueDetail(om.readTree(json));
        assertThat(d.description()).isEqualTo("본문");
        assertThat(d.priorityName()).isEqualTo("높음");
        assertThat(d.comments()).hasSize(1);                 // notes 빈 journal 제외
        assertThat(d.comments().get(0).notes()).isEqualTo("댓글1");
    }

    @Test void parseCurrentLogin_reads_user_login() throws Exception {
        String json = """
        {"user":{"id":3,"login":"jdoe","firstname":"J"}}""";
        assertThat(RedmineJson.parseCurrentLogin(om.readTree(json))).isEqualTo("jdoe");
    }
}
