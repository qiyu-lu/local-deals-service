package com.localdeals.controller;

import com.localdeals.config.WebConfig;
import com.localdeals.config.WebExceptionAdvice;
import com.localdeals.dto.BlogLikeCommandResult;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.interctptor.LoginInterceptor;
import com.localdeals.service.IBlogService;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BlogControllerLikeMvcTest {

    private static final long BLOG_ID = 41L;

    private IBlogService blogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        blogService = mock(IBlogService.class);
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", blogService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(new LoginInterceptor(
                        WebConfig.PUBLIC_GET_PATHS, WebConfig.PUBLIC_POST_PATHS))
                .build();
    }

    @AfterEach
    void clearUserContext() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousLikeRequestIsRejectedBeforeControllerInvocation() throws Exception {
        mockMvc.perform(put("/blog/{id}/like", BLOG_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(blogService);
    }

    @Test
    void authenticatedPutSetsDesiredLikeAndReturnsCommandOutcome() throws Exception {
        authenticate();
        when(blogService.setBlogLiked(BLOG_ID, true))
                .thenReturn(Result.ok(BlogLikeCommandResult.changed(true)));

        mockMvc.perform(put("/blog/{id}/like", BLOG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.desired").value(true))
                .andExpect(jsonPath("$.data.changed").value(true));

        verify(blogService).setBlogLiked(BLOG_ID, true);
    }

    @Test
    void authenticatedDeleteSetsDesiredUnlikeAndReturnsCommandOutcome() throws Exception {
        authenticate();
        when(blogService.setBlogLiked(BLOG_ID, false))
                .thenReturn(Result.ok(BlogLikeCommandResult.changed(false)));

        mockMvc.perform(delete("/blog/{id}/like", BLOG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.desired").value(false))
                .andExpect(jsonPath("$.data.changed").value(true));

        verify(blogService).setBlogLiked(BLOG_ID, false);
    }

    @Test
    void legacyRouteWithoutDesiredStateIsBadRequestAndDoesNotInvokeService() throws Exception {
        authenticate();

        mockMvc.perform(put("/blog/like/{id}", BLOG_ID))
                .andExpect(status().isBadRequest());

        verify(blogService, never()).setBlogLiked(BLOG_ID, true);
        verify(blogService, never()).setBlogLiked(BLOG_ID, false);
    }

    @Test
    void legacyRouteWithDesiredStateDelegatesExplicitly() throws Exception {
        authenticate();
        when(blogService.setBlogLiked(BLOG_ID, false))
                .thenReturn(Result.ok(BlogLikeCommandResult.unchanged(false)));

        mockMvc.perform(put("/blog/like/{id}", BLOG_ID)
                        .param("liked", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.desired").value(false))
                .andExpect(jsonPath("$.data.changed").value(false));

        verify(blogService).setBlogLiked(BLOG_ID, false);
    }

    @Test
    void missingBlogIsReturnedAsNotFound() throws Exception {
        authenticate();
        doThrow(new ApiStatusException(HttpStatus.NOT_FOUND, "笔记不存在!"))
                .when(blogService).setBlogLiked(BLOG_ID, true);

        mockMvc.perform(put("/blog/{id}/like", BLOG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void invalidBlogIdIsReturnedAsBadRequest() throws Exception {
        authenticate();
        doThrow(new IllegalArgumentException("blogId must be positive"))
                .when(blogService).setBlogLiked(0L, true);

        mockMvc.perform(put("/blog/{id}/like", 0L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void authenticate() {
        UserDTO user = new UserDTO();
        user.setId(73L);
        UserHolder.saveUser(user);
    }
}
