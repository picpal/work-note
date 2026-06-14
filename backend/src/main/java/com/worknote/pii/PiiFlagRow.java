package com.worknote.pii;

public record PiiFlagRow(
    String nodeId, String status, String types, String detectedAt,
    String requestedBy, String requestedAt, String requestReason,
    String decidedBy, String decidedAt, String decisionReason
) {}
