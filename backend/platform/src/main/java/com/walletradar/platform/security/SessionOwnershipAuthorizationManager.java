package com.walletradar.platform.security;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;

/**
 * Stateless ownership check for session-scoped endpoints.
 * Compares the {@code sessionId} claim in the JWT against the {@code {sessionId}} path segment.
 * Path pattern: {@code /api/v1/sessions/{sessionId}[/**]}
 */
public class SessionOwnershipAuthorizationManager
        implements ReactiveAuthorizationManager<AuthorizationContext> {

    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> authMono,
                                              AuthorizationContext context) {
        String sessionIdFromPath = extractSessionId(
                context.getExchange().getRequest().getPath().value());

        if (sessionIdFromPath == null) {
            return Mono.just(new AuthorizationDecision(false));
        }

        return authMono
                .filter(Authentication::isAuthenticated)
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwt -> {
                    String jwtSessionId = jwt.getToken().getClaimAsString("sessionId");
                    return new AuthorizationDecision(sessionIdFromPath.equals(jwtSessionId));
                })
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /** Extracts position 5 (1-based) from {@code /api/v1/sessions/{sessionId}/...}. */
    private static String extractSessionId(String path) {
        // split("/api/v1/sessions/{id}/foo") → ["", "api", "v1", "sessions", "{id}", "foo"]
        String[] parts = path.split("/");
        return parts.length >= 5 ? parts[4] : null;
    }
}
