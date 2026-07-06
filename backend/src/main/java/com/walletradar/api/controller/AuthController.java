package com.walletradar.api.controller;

import com.walletradar.auth.AuthProperties;
import com.walletradar.auth.AuthTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Authentication status and lifecycle endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/v1/auth/me} — returns identity and canonical sessionId; always 200
 *       (returns {@code authenticated:false} when no valid JWT cookie present).</li>
 *   <li>{@code POST /api/v1/auth/logout} — clears the {@code wr_auth} cookie.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthProperties authProperties;

    @GetMapping("/me")
    public Mono<Map<String, Object>> me() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth instanceof JwtAuthenticationToken && auth.isAuthenticated())
                .cast(JwtAuthenticationToken.class)
                .map(jwt -> Map.<String, Object>of(
                        "authenticated", true,
                        "provider", jwt.getToken().getClaimAsString(AuthTokenService.CLAIM_PROVIDER),
                        "email", jwt.getToken().getClaimAsString(AuthTokenService.CLAIM_EMAIL),
                        "displayName", jwt.getToken().getClaimAsString(AuthTokenService.CLAIM_NAME),
                        "pictureUrl", jwt.getToken().getClaimAsString(AuthTokenService.CLAIM_PICTURE),
                        "sessionId", jwt.getToken().getClaimAsString(AuthTokenService.CLAIM_SESSION_ID)
                ))
                .defaultIfEmpty(Map.of("authenticated", false));
    }

    @PostMapping("/logout")
    public Mono<Map<String, Object>> logout(ServerWebExchange exchange) {
        ResponseCookie clearCookie = ResponseCookie.from(authProperties.getCookie().getName(), "")
                .httpOnly(true)
                .secure(authProperties.getCookie().isSecure())
                .sameSite(authProperties.getCookie().getSameSite())
                .path(authProperties.getCookie().getPath())
                .maxAge(Duration.ZERO)
                .build();
        exchange.getResponse().addCookie(clearCookie);
        return Mono.just(Map.of("authenticated", false));
    }
}
