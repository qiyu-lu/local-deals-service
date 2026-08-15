package com.localdeals.service;

import com.localdeals.config.AdminProperties;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminClientIpResolverTest {

    @Test
    void trustedProxyCanSupplyAValidatedRealIp() {
        AdminProperties properties = new AdminProperties();
        properties.setTrustedProxies(Arrays.asList("127.0.0.1", "172.16.0.0/12", "172.30.55.10"));
        AdminClientIpResolver resolver = new AdminClientIpResolver(properties);
        HttpServletRequest request = request("172.30.55.10", "203.0.113.9", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void untrustedDirectPeerCannotSpoofProxyHeaders() {
        AdminProperties properties = new AdminProperties();
        properties.setTrustedProxies(Collections.singletonList("127.0.0.1"));
        AdminClientIpResolver resolver = new AdminClientIpResolver(properties);
        HttpServletRequest request = request("198.51.100.20", "203.0.113.9", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void malformedRealIpFallsBackToFirstForwardedAddressOnlyForTrustedProxy() {
        AdminProperties properties = new AdminProperties();
        properties.setTrustedProxies(Collections.singletonList("127.0.0.1"));
        AdminClientIpResolver resolver = new AdminClientIpResolver(properties);
        HttpServletRequest request = request(
                "127.0.0.1", "attacker.example", "2001:db8::8, 127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:8");
    }

    @Test
    void invalidTrustedProxyConfigurationFailsClosedAtStartup() {
        AdminProperties properties = new AdminProperties();
        properties.setTrustedProxies(Collections.singletonList("not-an-ip"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AdminClientIpResolver(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid trusted admin proxy");
    }

    @Test
    void invalidDirectPeerDoesNotAcceptForwardedHeaders() {
        AdminProperties properties = new AdminProperties();
        properties.setTrustedProxies(Collections.singletonList("127.0.0.1"));
        AdminClientIpResolver resolver = new AdminClientIpResolver(properties);

        assertThat(resolver.resolve(request(
                "not-an-ip", "203.0.113.9", "198.51.100.1"))).isEqualTo("unknown");
    }

    private HttpServletRequest request(String peer, String realIp, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(peer);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
