package com.gilbom.storage.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gilbom.storage.StorageProperties;

class RobotImageApiKeyIssuanceServiceTest {

    @Test
    void rotatesTheActiveKeyAndReturnsThePlaintextOnlyToTheCaller()
            throws Exception {
        UUID robotId = UUID.fromString(
                "f59c163e-f080-40ee-81ca-94b9d8f235bf");
        String apiKey =
                "gik_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RobotImageApiKeySecretGenerator generator =
                mock(RobotImageApiKeySecretGenerator.class);
        StorageProperties properties = new StorageProperties();
        properties.setUploadUrl("https://example.test/api/v1/images");

        when(generator.generate()).thenReturn(apiKey);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);

        IssuedRobotImageApiKey issued =
                new RobotImageApiKeyIssuanceService(
                        jdbcTemplate,
                        generator,
                        properties)
                        .rotate(robotId);

        assertThat(issued.uploadUrl())
                .isEqualTo("https://example.test/api/v1/images");
        assertThat(issued.apiKey()).isEqualTo(apiKey);
        assertThat(issued.keyPrefix()).isEqualTo(apiKey.substring(0, 12));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(
                sql.capture(),
                arguments.capture());
        List<Object[]> calls = arguments.getAllValues();
        assertThat(sql.getAllValues().get(0)).contains("key_status = 'REVOKED'");
        assertThat(calls.get(0)).containsExactly(robotId);
        assertThat(sql.getAllValues().get(1))
                .contains("INSERT INTO storage.robot_image_api_key");
        assertThat(calls.get(1)[1]).isEqualTo(robotId);
        assertThat(calls.get(1)[2]).isEqualTo(apiKey.substring(0, 12));
        assertThat(calls.get(1)[3]).isEqualTo(sha256Hex(apiKey));
        assertThat(calls.get(1)).doesNotContain(apiKey);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
