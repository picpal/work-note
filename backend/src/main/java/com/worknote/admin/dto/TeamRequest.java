package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamRequest(@NotBlank @Size(max = 64) String name) {}
