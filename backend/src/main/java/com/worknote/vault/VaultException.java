package com.worknote.vault;

/** vault 도메인 예외. status로 HTTP 매핑(404/409/422/403)을 ApiExceptionHandler에서 결정. */
public class VaultException extends RuntimeException {

    public enum Status { NOT_FOUND, CONFLICT, INVALID, FORBIDDEN }

    private final Status status;

    public VaultException(Status status, String message) {
        super(message);
        this.status = status;
    }

    public Status status() {
        return status;
    }

    public static VaultException notFound(String message) {
        return new VaultException(Status.NOT_FOUND, message);
    }

    public static VaultException conflict(String message) {
        return new VaultException(Status.CONFLICT, message);
    }

    public static VaultException invalid(String message) {
        return new VaultException(Status.INVALID, message);
    }

    public static VaultException forbidden(String message) {
        return new VaultException(Status.FORBIDDEN, message);
    }
}
