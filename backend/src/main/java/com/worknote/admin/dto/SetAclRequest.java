package com.worknote.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SetAclRequest(@NotNull List<@Valid AclEntryRequest> entries) {}
