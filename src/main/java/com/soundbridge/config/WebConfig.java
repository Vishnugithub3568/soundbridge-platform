package com.soundbridge.config;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@SuppressWarnings("null")
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final String[] allowedOriginPatterns;

    public WebConfig(
        @Value("${cors.allowed-origins:http://localhost:5173}") String allowedOrigins,
        @Value("${cors.allowed-origin-patterns:}") String allowedOriginPatterns
    ) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .collect(Collectors.toList())
            .toArray(new String[0]);

        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .collect(Collectors.toList())
            .toArray(new String[0]);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var mapping = registry.addMapping("/**")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*");

        if (allowedOrigins.length > 0) {
            mapping.allowedOrigins(allowedOrigins);
        }

        if (allowedOriginPatterns.length > 0) {
            mapping.allowedOriginPatterns(allowedOriginPatterns);
        }
    }
}
