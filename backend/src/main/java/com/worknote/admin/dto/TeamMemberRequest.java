package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamMemberRequest(@NotBlank @Size(max = 64) String userId) {}
