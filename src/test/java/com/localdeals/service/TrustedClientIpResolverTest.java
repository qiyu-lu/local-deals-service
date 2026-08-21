package com.localdeals.service;

import com.localdeals.config.ClientIpProperties;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedClientIpResolverTest {

    @Test
    void trustedProxyCanSupplyAValidatedRealIp() {
        ClientIpProperties properties = properties("127.0.0.1", "172.16.0.0/12", "172.30.55.10");
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties);
        HttpServletRequest request = request("172.30.55.10", "203.0.113.9", "198.51.100.1");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void untrustedDirectPeerCannotSpoofProxyHeaders() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties("127.0.0.1"));

        assertThat(resolver.resolve(request("198.51.100.20", "203.0.113.9", "203.0.113.8")))
                .isEqualTo("198.51.100.20");
    }

    @Test
    void malformedRealIpFallsBackToFirstForwardedAddressOnlyForTrustedProxy() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties("127.0.0.1"));
        HttpServletRequest request = request(
                "127.0.0.1", "attacker.example", "2001:db8::8, 127.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:8");
    }

    @Test
    void invalidTrustedProxyConfigurationFailsClosedAtStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TrustedClientIpResolver(properties("not-an-ip")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid trusted proxy");
    }

    @Test
    void invalidDirectPeerDoesNotAcceptForwardedHeaders() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties("127.0.0.1"));

        assertThat(resolver.resolve(request(
                "not-an-ip", "203.0.113.9", "198.51.100.1"))).isEqualTo("unknown");
    }

    @Test
    void ipv4AndIpv6CidrBoundariesAreExact() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(
                properties("10.0.0.0/8", "2001:db8::/32"));

        assertThat(resolver.resolve(request("10.2.3.4", "203.0.113.4", null)))
                .isEqualTo("203.0.113.4");
        assertThat(resolver.resolve(request("11.2.3.4", "203.0.113.5", null)))
                .isEqualTo("11.2.3.4");
        assertThat(resolver.resolve(request("2001:db8::10", "2001:db9::1", null)))
                .isEqualTo("2001:db9:0:0:0:0:0:1");
    }

    private ClientIpProperties properties(String... ranges) {
        ClientIpProperties properties = new ClientIpProperties();
        properties.setTrustedProxies(ranges == null
                ? Collections.emptyList() : Arrays.asList(ranges));
        return properties;
    }

    private HttpServletRequest request(String peer, String realIp, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(peer);
        when(request.getHeader("X-Real-IP")).thenReturn(realIp);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
