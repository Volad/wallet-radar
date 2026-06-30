package com.walletradar.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues signed HS256 JWT tokens for authenticated sessions.
 * Validation is handled by Spring Security's NimbusReactiveJwtDecoder in SecurityConfig.
 */
@Service
public class AuthTokenService {

    public static final String CLAIM_SESSION_ID = "sessionId";
    public static final String CLAIM_PROVIDER = "provider";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_PICTURE = "picture";

    private final MACSigner signer;
    private final AuthProperties authProperties;

    public AuthTokenService(AuthProperties authProperties) throws JOSEException {
        this.authProperties = authProperties;
        byte[] secretBytes = authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        this.signer = new MACSigner(secretBytes);
    }

    public String mint(
            String sessionId,
            IdentityProvider provider,
            String subject,
            String email,
            String displayName,
            String pictureUrl
    ) {
        try {
            long expSeconds = authProperties.getJwt().getExpirationSeconds();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim(CLAIM_SESSION_ID, sessionId)
                    .claim(CLAIM_PROVIDER, provider.name())
                    .claim(CLAIM_EMAIL, email)
                    .claim(CLAIM_NAME, displayName)
                    .claim(CLAIM_PICTURE, pictureUrl)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(expSeconds)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to mint JWT", e);
        }
    }
}
