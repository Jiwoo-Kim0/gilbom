package com.gilbom.robot.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class MqttCredentialSecretGeneratorTest {

    private final MqttCredentialSecretGenerator generator =
            new MqttCredentialSecretGenerator();

    @Test
    void generatesThirtyTwoRandomBytesAsUrlSafeBase64() {
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first)
                .hasSize(43)
                .matches("^[A-Za-z0-9_-]+$")
                .isNotEqualTo(second);
        assertThat(Base64.getUrlDecoder().decode(first))
                .hasSize(MqttCredentialSecretGenerator.SECRET_BYTES);
    }
}
