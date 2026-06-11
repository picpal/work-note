package com.worknote;

import com.worknote.auth.AuthException;
import com.worknote.vault.VaultException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** VaultException.status → HTTP 상태 매핑. body는 {"error": message} 단일 계약. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VaultException.class)
    public ResponseEntity<Map<String, String>> vault(VaultException e) {
        HttpStatus status = switch (e.status()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> auth(AuthException e) {
        HttpStatus status = switch (e.status()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .orElse("잘못된 요청 본문입니다");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
