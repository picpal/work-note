package com.worknote.pii;

import java.util.List;

/** PATCH 응답·평가 결과. status ∈ none/suspected/requested/exempted/rejected. */
public record PiiEval(String status, List<String> types) {}
