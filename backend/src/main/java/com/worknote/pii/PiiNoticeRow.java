package com.worknote.pii;

public record PiiNoticeRow(
    Long id, String nodeId, String recipient, String kind,
    String message, String sentBy, String sentAt, String ackAt
) {}
