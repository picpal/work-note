package com.worknote.auth.dto;

import java.util.Set;

public record MeResponse(String id, String emp, String name, String email, String roleId,
                         Set<String> caps, TotpInfo totp) {
    public record TotpInfo(boolean enabled, boolean enforced, boolean graceExpired, boolean emailPresent) {}
}
