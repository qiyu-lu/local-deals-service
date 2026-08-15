package com.localdeals.interctptor;

import com.localdeals.config.WebConfig;
import com.localdeals.dto.UserDTO;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoginInterceptorTest {

    private final LoginInterceptor interceptor = new LoginInterceptor(
            WebConfig.PUBLIC_GET_PATHS, WebConfig.PUBLIC_POST_PATHS);

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousPublicGetIsAllowed() throws Exception {
        MockHttpServletRequest request = request("GET", "/shop/12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void samePublicPathWithWriteMethodRequiresLogin() throws Exception {
        MockHttpServletRequest request = request("POST", "/shop/12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void onlyPostMethodIsAnonymousForLoginEndpoint() throws Exception {
        MockHttpServletResponse postResponse = new MockHttpServletResponse();
        MockHttpServletResponse getResponse = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("POST", "/user/login"), postResponse, new Object())).isTrue();
        assertThat(interceptor.preHandle(request("GET", "/user/login"), getResponse, new Object())).isFalse();
        assertThat(getResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void publicReadSupportsHeadAndTrailingSlash() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("HEAD", "/shop/12/"), response, new Object())).isTrue();
    }

    @Test
    void corsPreflightDoesNotRequireLogin() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("OPTIONS", "/voucher/seckill"), response, new Object())).isTrue();
    }

    @Test
    void anonymousWriteEndpointIsRejected() throws Exception {
        MockHttpServletRequest request = request("POST", "/voucher/seckill");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void authenticatedUserCanAccessProtectedEndpoint() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        MockHttpServletRequest request = request("DELETE", "/upload/blog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
