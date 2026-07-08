package com.walletradar.api.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * CORS settings for API endpoints consumed by frontend SPA.
 */
@ConfigurationProperties(prefix = "walletradar.api.cors")
@NoArgsConstructor
@Getter
@Setter
public class ApiCorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:4200",
            "http://127.0.0.1:4200",
            "http://localhost:4206",
            "http://127.0.0.1:4206",
            "http://localhost:4300",
            "http://127.0.0.1:4300"
    ));
    private List<String> allowedMethods = new ArrayList<>(List.of(
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS"
    ));
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
    private long maxAgeSeconds = 3600;

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : new ArrayList<>();
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods != null ? allowedMethods : new ArrayList<>();
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders != null ? allowedHeaders : new ArrayList<>();
    }
}
