package com.worknote;

import com.worknote.auth.AuthException;
import com.worknote.redmine.RedmineException;
import com.worknote.vault.VaultException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
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

    /** multipart 상한(64MB) 초과 → 422 단일화 (UploadPolicy 위반과 같은 상태코드). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "파일이 너무 큽니다"));
    }

    @ExceptionHandler(RedmineException.Auth.class)
    public ResponseEntity<Map<String, String>> redmineAuth(RedmineException.Auth e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RedmineException.NotFound.class)
    public ResponseEntity<Map<String, String>> redmineNotFound(RedmineException.NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RedmineException.Upstream.class)
    public ResponseEntity<Map<String, String>> redmineUpstream(RedmineException.Upstream e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
