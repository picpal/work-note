package com.worknote.auth;

/** app_user 1행. status: pending|active|disabled. */
public record UserRow(String id, String emp, String email, String name,
                      String roleId, String status, String lastLogin) {}
