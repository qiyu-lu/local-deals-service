package com.localdeals.config;

import com.localdeals.dto.Result;
import com.localdeals.exception.ApiStatusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(ApiStatusException.class)
    public ResponseEntity<Result> handleApiStatusException(ApiStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
