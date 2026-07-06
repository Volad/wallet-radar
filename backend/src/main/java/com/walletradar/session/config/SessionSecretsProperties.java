package com.walletradar.session.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local file-backed encryption settings for session-owned secrets.
 */
@ConfigurationProperties(prefix = "walletradar.session.secrets")
@Getter
@Setter
public class SessionSecretsProperties {

    /**
     * Relative or absolute path to the base64-encoded AES-256-GCM key.
     */
    private String keyFile = ".secrets/session-crypto.key";

    /**
     * Logical key version stored alongside ciphertext for future rotation.
     */
    private String keyVersion = "local-v1";
}
