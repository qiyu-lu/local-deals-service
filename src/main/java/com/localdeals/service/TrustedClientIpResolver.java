package com.localdeals.service;

import com.localdeals.config.ClientIpProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves proxy headers only when the direct peer is explicitly trusted. */
@Component
public class TrustedClientIpResolver {
    private static final int MAX_IP_LITERAL_LENGTH = 64;

    private final List<IpRange> trustedProxyRanges;

    public TrustedClientIpResolver(ClientIpProperties properties) {
        List<String> configured = properties == null
                ? Collections.emptyList()
                : properties.getTrustedProxies();
        List<IpRange> ranges = new ArrayList<>();
        if (configured != null) {
            for (String value : configured) {
                if (StringUtils.hasText(value)) {
                    ranges.add(IpRange.parse(value.trim()));
                }
            }
        }
        this.trustedProxyRanges = Collections.unmodifiableList(ranges);
    }

    public String resolve(HttpServletRequest request) {
        String directPeer = canonicalIp(request == null ? null : request.getRemoteAddr());
        if (directPeer == null) {
            return "unknown";
        }
        if (!isTrustedProxy(directPeer)) {
            return directPeer;
        }

        String realIp = canonicalIp(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String firstHop = forwardedFor.split(",", 2)[0].trim();
            String forwardedIp = canonicalIp(firstHop);
            if (forwardedIp != null) {
                return forwardedIp;
            }
        }
        return directPeer;
    }

    private boolean isTrustedProxy(String directPeer) {
        byte[] address = parseIpLiteral(directPeer);
        if (address == null) {
            return false;
        }
        for (IpRange range : trustedProxyRanges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalIp(String value) {
        byte[] address = parseIpLiteral(value);
        if (address == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    private static byte[] parseIpLiteral(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_IP_LITERAL_LENGTH ||
                (!value.matches("[0-9.]+") && !value.matches("[0-9a-fA-F:]+"))) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static final class IpRange {
        private final byte[] network;
        private final int prefixLength;

        private IpRange(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        private static IpRange parse(String configured) {
            String[] parts = configured.split("/", -1);
            if (parts.length > 2) {
                throw new IllegalStateException("Invalid trusted proxy range: " + configured);
            }
            byte[] network = parseIpLiteral(parts[0]);
            if (network == null) {
                throw new IllegalStateException("Invalid trusted proxy address: " + configured);
            }
            int prefix = network.length * 8;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Invalid trusted proxy prefix: " + configured, e);
                }
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalStateException("Invalid trusted proxy prefix: " + configured);
            }
            return new IpRange(network, prefix);
        }

        private boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
