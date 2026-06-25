package com.worknote.redmine;

import java.util.List;

public final class RedmineDtos {
    public record RedmineIssueSummary(
        long id, String subject, String statusName, String assignedToName,
        String projectName, String updatedOn) {}

    public record RedmineComment(String userName, String createdOn, String notes) {}

    public record RedmineIssueDetail(
        long id, String subject, String description, String statusName,
        String assignedToName, String projectName, String priorityName,
        String dueDate, String updatedOn, List<RedmineComment> comments) {}

    public record RedmineQuery(
        String query, boolean assignedToMe, String statusId,
        String projectId, int offset, int limit) {}

    private RedmineDtos() {}
}
