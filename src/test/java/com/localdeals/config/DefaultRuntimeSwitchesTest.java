package com.localdeals.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code mvn spring-boot:run} must start the system that is demonstrated: every runtime switch
 * defaults to on.
 */
class DefaultRuntimeSwitchesTest {

    private static final Pattern SWITCH_DEFAULT =
            Pattern.compile("\\$\\{(LOCAL_DEALS_[A-Z0-9_]+_ENABLED):([^}]*)}");

    @Test
    void everyEnabledSwitchDefaultsToTrue() throws IOException {
        String yaml = applicationYaml();
        Map<String, String> defaults = new LinkedHashMap<>();
        Matcher matcher = SWITCH_DEFAULT.matcher(yaml);
        while (matcher.find()) {
            defaults.put(matcher.group(1), matcher.group(2));
        }

        assertThat(defaults).isNotEmpty();
        assertThat(defaults).allSatisfy((name, value) ->
                assertThat(value).as(name).isEqualTo("true"));
    }

    private static String applicationYaml() throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource("application.yaml").getInputStream(), StandardCharsets.UTF_8);
    }
}
