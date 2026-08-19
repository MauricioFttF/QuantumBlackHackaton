package com.seuprojeto.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the React frontend, served from a different port, to call this API from a browser.
 * Without this every request fails CORS preflight even though the server answers 200.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private final WebProperties properties;

    public CorsConfig(WebProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("CORS enabled for /api/** from origins {}", properties.corsAllowedOrigins());
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }
}
