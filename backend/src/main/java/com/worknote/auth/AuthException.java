package com.worknote.auth;

/** 인증 도메인 예외. UNAUTHORIZED→401, FORBIDDEN→403 (ApiExceptionHandler). */
public class AuthException extends RuntimeException {

    public enum Status { UNAUTHORIZED, FORBIDDEN }

    private final Status status;

    public AuthException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public Status status() {
        return status;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(Status.UNAUTHORIZED, message);
    }

    public static AuthException forbidden(String message) {
        return new AuthException(Status.FORBIDDEN, message);
    }
}
