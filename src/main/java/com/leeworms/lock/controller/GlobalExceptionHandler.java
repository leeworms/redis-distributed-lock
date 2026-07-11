package com.leeworms.lock.controller;

import com.leeworms.lock.exception.DuplicateRequestException;
import com.leeworms.lock.exception.ResourceLockedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateRequestException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "DUPLICATE_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(ResourceLockedException.class)
    public ResponseEntity<Map<String, String>> handleLocked(ResourceLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "RESOURCE_LOCKED", "message", e.getMessage()));
    }
}
