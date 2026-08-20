package com.localdeals.service.impl;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.dto.BlogLikeCommandResult;
import com.localdeals.dto.UserDTO;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.BlogLikeCommandService;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlogServiceLikeContractTest {

    private static final long USER_ID = 700_101L;

    @Mock
    private BlogLikeCommandService commandService;

    private BlogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl();
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWriteEnabled(true);
        ReflectionTestUtils.setField(service, "blogLikeProperties", properties);
        ReflectionTestUtils.setField(service, "blogLikeCommandService", commandService);
        ReflectionTestUtils.setField(service, "metrics",
                new LocalDealsMetrics(new SimpleMeterRegistry()));

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void missingBlogUsesHttpNotFoundInsteadOfAnOkFailureEnvelope() {
        when(commandService.setLiked(41L, USER_ID, true))
                .thenReturn(BlogLikeCommandResult.notFound(true));

        assertThatThrownBy(() -> service.setBlogLiked(41L, true))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
