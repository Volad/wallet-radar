package com.walletradar.platform.security;

import com.walletradar.auth.AuthProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the JWT from the HttpOnly cookie {@code wr_auth} and produces a
 * {@link BearerTokenAuthenticationToken} for the resource-server JWT decoder.
 */
public class CookieBearerTokenConverter implements ServerAuthenticationConverter {

    private final String cookieName;

    public CookieBearerTokenConverter(AuthProperties authProperties) {
        this.cookieName = authProperties.getCookie().getName();
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return Mono.justOrEmpty(exchange.getRequest().getCookies().getFirst(cookieName))
                .map(cookie -> (Authentication) new BearerTokenAuthenticationToken(cookie.getValue()));
    }
}
