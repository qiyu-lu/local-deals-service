package com.localdeals.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class NginxManagementBoundaryTest {

    @Test
    void actuatorDenialPrecedesTheGenericApiProxy() throws Exception {
        String nginx = new String(Files.readAllBytes(Paths.get("frontend/nginx.conf")),
                StandardCharsets.UTF_8);

        int denial = nginx.indexOf("location ^~ /api/actuator");
        int proxy = nginx.indexOf("location /api/");
        assertThat(denial).isGreaterThanOrEqualTo(0).isLessThan(proxy);
        assertThat(nginx.substring(denial, proxy)).contains("return 404;");
    }
}
