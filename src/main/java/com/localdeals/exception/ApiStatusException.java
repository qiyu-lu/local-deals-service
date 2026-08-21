package com.localdeals.exception;

import org.springframework.http.HttpStatus;

public class ApiStatusException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiStatusException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiStatusException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
