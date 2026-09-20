package com.localdeals.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebConfigMessageConverterTest {

    @Test
    void theApiAnswersJsonWhateverADriverPutsOnTheClasspath() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new ByteArrayHttpMessageConverter(),
                new StringHttpMessageConverter(),
                new MappingJackson2XmlHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter()));

        new WebConfig().extendMessageConverters(converters);

        assertThat(converters)
                .as("nothing left that would answer a */* request in XML")
                .noneMatch(converter -> converter.canWrite(Object.class, MediaType.APPLICATION_XML));
        assertThat(converters)
                .as("JSON is still there")
                .anyMatch(converter -> converter.canWrite(Object.class, MediaType.APPLICATION_JSON));
    }
}
