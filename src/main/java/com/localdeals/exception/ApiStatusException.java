package com.localdeals.exception;

import org.springframework.http.HttpStatus;

public class ApiStatusException extends RuntimeException {
    private final HttpStatus status;

    public ApiStatusException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
