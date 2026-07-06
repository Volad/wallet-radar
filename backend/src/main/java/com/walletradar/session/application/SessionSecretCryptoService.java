package com.walletradar.session.application;

import com.walletradar.domain.session.UserSession;
import com.walletradar.session.config.SessionSecretsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts session-owned provider credentials using a local file-backed
 * AES-256-GCM key.
 */
@Service
@RequiredArgsConstructor
public class SessionSecretCryptoService {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SessionSecretsProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    private SecretKey activeKey;

    @PostConstruct
    void initialize() {
        this.activeKey = loadKey(Path.of(properties.getKeyFile()));
    }

    public UserSession.EncryptedSecret encrypt(String plaintext, String maskedKey) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            UserSession.EncryptedSecret secret = new UserSession.EncryptedSecret();
            secret.setKeyVersion(properties.getKeyVersion());
            secret.setNonceB64(Base64.getEncoder().encodeToString(nonce));
            secret.setCiphertextB64(Base64.getEncoder().encodeToString(ciphertext));
            secret.setMaskedKey(maskedKey);
            return secret;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt session secret", exception);
        }
    }

    public String decrypt(UserSession.EncryptedSecret encryptedSecret) {
        if (encryptedSecret == null) {
            return null;
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(encryptedSecret.getNonceB64());
            byte[] ciphertext = Base64.getDecoder().decode(encryptedSecret.getCiphertextB64());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt session secret", exception);
        }
    }

    public static String generateBase64Key() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            return Base64.getEncoder().encodeToString(keyGenerator.generateKey().getEncoded());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate AES key", exception);
        }
    }

    private SecretKey loadKey(Path path) {
        try {
            String base64 = Files.readString(path).trim();
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load session secrets key from " + path, exception);
        }
    }
}
