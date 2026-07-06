package com.walletradar.security;

import com.walletradar.auth.AuthProperties;
import com.walletradar.auth.AuthTokenService;
import com.walletradar.auth.IdentityProvider;
import com.walletradar.domain.session.UserSession;
import com.walletradar.domain.session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * After successful Google OAuth2 login:
 * <ol>
 *   <li>Resolves or creates the canonical {@link UserSession} for this identity.</li>
 *   <li>Mints a signed JWT and writes it as an HttpOnly cookie {@code wr_auth}.</li>
 *   <li>Redirects to the frontend settings page.</li>
 * </ol>
 * Anonymous session auto-claim is intentionally omitted; binding to an existing session
 * is a separate manual operation.
 */
@Slf4j
@RequiredArgsConstructor
public class OAuthSuccessHandler implements ServerAuthenticationSuccessHandler {

    private final UserSessionRepository userSessionRepository;
    private final AuthTokenService authTokenService;
    private final AuthProperties authProperties;

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange,
                                               Authentication authentication) {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauthUser = oauthToken.getPrincipal();

        String sub = oauthUser.getAttribute("sub");
        String email = oauthUser.getAttribute("email");
        Boolean emailVerified = oauthUser.getAttribute("email_verified");
        String displayName = oauthUser.getAttribute("name");
        String pictureUrl = oauthUser.getAttribute("picture");

        return Mono.fromCallable(() ->
                resolveSession(sub, email, emailVerified, displayName, pictureUrl)
        )
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(session -> {
            String token = authTokenService.mint(
                    session.getId(), IdentityProvider.GOOGLE,
                    sub, email, displayName, pictureUrl);
            setAuthCookie(webFilterExchange, token);
            return redirect(webFilterExchange);
        });
    }

    private UserSession resolveSession(String sub, String email, Boolean emailVerified,
                                        String displayName, String pictureUrl) {
        return userSessionRepository
                .findByIdentityProviderAndIdentitySubject(IdentityProvider.GOOGLE, sub)
                .orElseGet(() -> createSession(sub, email, emailVerified, displayName, pictureUrl));
    }

    private UserSession createSession(String sub, String email, Boolean emailVerified,
                                       String displayName, String pictureUrl) {
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);

        UserSession.IdentityBinding binding = new UserSession.IdentityBinding();
        binding.setProvider(IdentityProvider.GOOGLE);
        binding.setSubject(sub);
        binding.setEmail(email);
        binding.setEmailVerified(emailVerified);
        binding.setDisplayName(displayName);
        binding.setPictureUrl(pictureUrl);
        binding.setLinkedAt(now);
        session.setIdentity(binding);

        log.info("Creating new session for Google identity sub={} email={}", sub, email);
        return userSessionRepository.save(session);
    }

    private void setAuthCookie(WebFilterExchange webFilterExchange, String token) {
        AuthProperties.CookieProperties cp = authProperties.getCookie();
        ResponseCookie cookie = ResponseCookie.from(cp.getName(), token)
                .httpOnly(true)
                .secure(cp.isSecure())
                .sameSite(cp.getSameSite())
                .path(cp.getPath())
                .maxAge(Duration.ofSeconds(cp.getMaxAgeSeconds()))
                .build();
        webFilterExchange.getExchange().getResponse().addCookie(cookie);
    }

    private Mono<Void> redirect(WebFilterExchange webFilterExchange) {
        var response = webFilterExchange.getExchange().getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(authProperties.getPostLoginRedirect()));
        return response.setComplete();
    }
}
