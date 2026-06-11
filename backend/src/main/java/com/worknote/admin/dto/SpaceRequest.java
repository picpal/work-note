package com.worknote.admin.dto;

import jakarta.validation.constraints.Size;

public record SpaceRequest(@Size(max = 64) String teamId) {}
