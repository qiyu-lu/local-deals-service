package com.localdeals.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localdeals.dto.Result;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class StableApiContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebExceptionAdvice advice = new WebExceptionAdvice();

    @Test
    void legacySuccessJsonAndStringOrderIdRemainCompatible() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok("9223372036854775807"));

        assertThat(json).isEqualTo("{\"success\":true,\"data\":\"9223372036854775807\"}");
        assertThat(json).doesNotContain("code");
    }

    @Test
    void codedFailureSerializesOnlyStableFields() throws Exception {
        String json = objectMapper.writeValueAsString(
                Result.fail(ApiErrorCodes.SEARCH_UNAVAILABLE, "搜索暂不可用"));

        assertThat(json).isEqualTo("{\"success\":false,\"code\":\"SEARCH_UNAVAILABLE\"," +
                "\"errorMsg\":\"搜索暂不可用\"}");
    }

    @Test
    void adviceUsesReal400429500And503Statuses() {
        assertResponse(advice.handleIllegalArgumentException(new IllegalArgumentException("bad")),
                400, ApiErrorCodes.INVALID_REQUEST);
        assertResponse(advice.handleApiStatusException(new ApiStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.READ_OVERLOADED, "busy")),
                429, ApiErrorCodes.READ_OVERLOADED);
        assertResponse(advice.handleApiStatusException(new ApiStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCodes.DATABASE_UNAVAILABLE, "down")),
                503, ApiErrorCodes.DATABASE_UNAVAILABLE);
        assertResponse(advice.handleRuntimeException(new RuntimeException("secret detail")),
                500, ApiErrorCodes.INTERNAL_ERROR);
    }

    private void assertResponse(ResponseEntity<Result> response, int status, String code) {
        assertThat(response.getStatusCodeValue()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo(code);
    }
}
