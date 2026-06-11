package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AclEntryRequest(
    @NotBlank @Pattern(regexp = "user|team|all") String principalType,
    @NotBlank @Size(max = 64) String principalId,
    @NotBlank @Pattern(regexp = "read|edit|deny") String grantType
) {}
