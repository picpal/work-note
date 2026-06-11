package com.worknote.vault.dto;

import java.util.List;

public record UpdateNodeRequest(String name, String content, List<String> tags) {}
