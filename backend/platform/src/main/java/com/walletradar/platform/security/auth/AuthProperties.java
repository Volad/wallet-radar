package com.walletradar.platform.security.auth;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "walletradar.auth")
@NoArgsConstructor
@Getter
@Setter
public class AuthProperties {

    private boolean enabled = false;
    private String postLoginRedirect = "/settings";

    @NestedConfigurationProperty
    private JwtProperties jwt = new JwtProperties();

    @NestedConfigurationProperty
    private CookieProperties cookie = new CookieProperties();

    @NoArgsConstructor
    @Getter
    @Setter
    public static class JwtProperties {
        private String secret = "dev-insecure-secret-change-in-prod";
        private long expirationSeconds = 2592000L; // 30 days
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class CookieProperties {
        private String name = "wr_auth";
        private int maxAgeSeconds = 2592000; // 30 days
        private boolean secure = true;
        private String sameSite = "Lax";
        private String path = "/";
    }
}
