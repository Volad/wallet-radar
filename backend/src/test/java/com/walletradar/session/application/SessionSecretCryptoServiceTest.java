package com.walletradar.session.application;

import com.walletradar.session.config.SessionSecretsProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SessionSecretCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsAndDecryptsSecrets() throws Exception {
        Path keyFile = tempDir.resolve("session-crypto.key");
        Files.writeString(keyFile, SessionSecretCryptoService.generateBase64Key());

        SessionSecretsProperties properties = new SessionSecretsProperties();
        properties.setKeyFile(keyFile.toString());
        properties.setKeyVersion("test-v1");

        SessionSecretCryptoService service = new SessionSecretCryptoService(properties);
        service.initialize();

        var encrypted = service.encrypt("{\"apiKey\":\"abc\",\"apiSecret\":\"def\"}", "abcd...wxyz");

        assertThat(encrypted.getKeyVersion()).isEqualTo("test-v1");
        assertThat(encrypted.getMaskedKey()).isEqualTo("abcd...wxyz");
        assertThat(service.decrypt(encrypted)).isEqualTo("{\"apiKey\":\"abc\",\"apiSecret\":\"def\"}");
    }

    @Test
    void generatesAes256CompatibleBase64Key() {
        byte[] decoded = Base64.getDecoder().decode(SessionSecretCryptoService.generateBase64Key());

        assertThat(decoded).hasSize(32);
    }
}
