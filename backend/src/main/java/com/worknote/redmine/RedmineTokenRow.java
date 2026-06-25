package com.worknote.redmine;

public record RedmineTokenRow(
    String userId,
    String tokenEnc,
    String redmineLogin,
    String lastVerifiedAt,
    String createdAt
) {}
