package com.walletradar.security;

import com.walletradar.auth.AuthProperties;
import com.walletradar.auth.AuthTokenService;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    // ── Disabled (dev / local) ────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "walletradar.auth.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityWebFilterChain permitAllChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(auth -> auth.anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.disable())
                .build();
    }

    // ── Enabled (prod) ────────────────────────────────────────────────────────

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "walletradar.auth.enabled", havingValue = "true")
    public SecurityWebFilterChain oauth2LoginChain(
            ServerHttpSecurity http,
            OAuthSuccessHandler successHandler) {

        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/oauth2/**", "/login/oauth2/**"))
                .authorizeExchange(auth -> auth.anyExchange().permitAll())
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler(successHandler)
                        .authenticationFailureHandler((exchange, ex) -> {
                            log.error("OAuth2 login failed: {}", ex.getMessage(), ex);
                            exchange.getExchange().getResponse().setStatusCode(
                                    org.springframework.http.HttpStatus.FOUND);
                            exchange.getExchange().getResponse().getHeaders()
                                    .setLocation(java.net.URI.create("/settings?auth_error=true"));
                            return exchange.getExchange().getResponse().setComplete();
                        })
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.disable())
                .build();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "walletradar.auth.enabled", havingValue = "true")
    public SecurityWebFilterChain apiChain(
            ServerHttpSecurity http,
            AuthProperties authProperties,
            SessionOwnershipAuthorizationManager ownershipManager) {

        byte[] secretBytes = authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        CookieBearerTokenConverter cookieConverter = new CookieBearerTokenConverter(authProperties);

        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**"))
                .authorizeExchange(auth -> auth
                        .pathMatchers("/api/v1/auth/me").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/sessions").authenticated()
                        .pathMatchers("/api/v1/sessions/{sessionId}", "/api/v1/sessions/{sessionId}/**")
                            .access(ownershipManager)
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                        .bearerTokenConverter(cookieConverter)
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.disable())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "walletradar.auth.enabled", havingValue = "true")
    public OAuthSuccessHandler oAuthSuccessHandler(
            UserSessionRepository userSessionRepository,
            AuthTokenService authTokenService,
            AuthProperties authProperties) {
        return new OAuthSuccessHandler(userSessionRepository, authTokenService, authProperties);
    }

    @Bean
    @ConditionalOnProperty(name = "walletradar.auth.enabled", havingValue = "true")
    public SessionOwnershipAuthorizationManager sessionOwnershipAuthorizationManager() {
        return new SessionOwnershipAuthorizationManager();
    }
}
