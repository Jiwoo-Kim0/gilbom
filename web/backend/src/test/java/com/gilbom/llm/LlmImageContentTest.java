package com.gilbom.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class LlmImageContentTest {

    @Test
    void copiesBytesAndRedactsThemFromToString() {
        byte[] source = jpegBytes(3);
        LlmImageContent image = new LlmImageContent(
                UUID.randomUUID(),
                "INC-001",
                source,
                "image/jpeg",
                640,
                480);

        source[2] = 99;
        byte[] firstRead = image.bytes();
        firstRead[2] = 88;

        assertThat(image.bytes()[2]).isEqualTo((byte) 3);
        assertThat(image.toString())
                .contains("bytes=[REDACTED]")
                .doesNotContain(java.util.Base64.getEncoder()
                        .encodeToString(image.bytes()));
    }

    @Test
    void rejectsNonJpegOrInvalidMetadata() {
        assertThatThrownBy(() -> new LlmImageContent(
                UUID.randomUUID(),
                "INC-001",
                new byte[] {1, 2, 3},
                "image/png",
                0,
                480))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .REPORT_VALIDATION_FAILED));
    }

    @Test
    void rejectsJpegThatStillContainsExifMetadata() {
        byte[] jpegWithExif = new byte[] {
                (byte) 0xff,
                (byte) 0xd8,
                (byte) 0xff,
                (byte) 0xe1,
                'E',
                'x',
                'i',
                'f',
                0,
                0,
                (byte) 0xff,
                (byte) 0xd9
        };

        assertThatThrownBy(() -> new LlmImageContent(
                UUID.randomUUID(),
                "INC-EXIF",
                jpegWithExif,
                "image/jpeg",
                640,
                480))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .REPORT_VALIDATION_FAILED));
    }

    @Test
    void requestAndResponseToStringRedactRawContent() {
        String sensitiveInput = "sensitive-prompt-input";
        String sensitiveOutput = "sensitive-provider-output";
        LlmGenerationRequest request =
                new LlmGenerationRequest(
                        java.util.Map.of(
                                "description",
                                sensitiveInput));
        LlmGenerationResponse response =
                new LlmGenerationResponse(
                        sensitiveOutput,
                        "test-model",
                        "report-v1",
                        LlmUsage.unknown());

        assertThat(request.toString())
                .contains("input=[REDACTED]")
                .doesNotContain(sensitiveInput);
        assertThat(response.toString())
                .contains("content=[REDACTED]")
                .doesNotContain(sensitiveOutput);
    }

    private static byte[] jpegBytes(int marker) {
        return new byte[] {
                (byte) 0xff,
                (byte) 0xd8,
                (byte) marker,
                (byte) 0xff,
                (byte) 0xd9
        };
    }
}
