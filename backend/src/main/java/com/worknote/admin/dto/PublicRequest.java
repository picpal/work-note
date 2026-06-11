package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PublicRequest(@NotBlank @Pattern(regexp = "public|exclude") String mode) {}
