package com.localdeals.interctptor;

import com.localdeals.config.AdminProperties;
import com.localdeals.dto.UserDTO;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccessInterceptorTest {

    private AdminProperties properties;
    private AdminAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new AdminProperties();
        interceptor = new AdminAccessInterceptor(properties);
    }

    @AfterEach
    void clearUserHolder() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousManagementWriteIsUnauthorized() {
        MockHttpServletResponse response = invoke("POST", "/voucher/seckill");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void ordinaryUserCannotMutateManagementResource() {
        loginAs(7L);

        MockHttpServletResponse response = invoke("PUT", "/shop");

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowlistedAdministratorCanMutateManagementResource() {
        properties.setUserIds("7, 9");
        loginAs(7L);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher/seckill");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void publicReadDoesNotRequireAdministratorRole() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shop/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private MockHttpServletResponse invoke(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean accepted = interceptor.preHandle(request, response, new Object());
        assertThat(accepted).isFalse();
        return response;
    }

    private void loginAs(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }
}
