package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.defect.DefectReportImageService;
import com.gilbom.defect.DefectReportImageService.DefectReportImage;
import com.gilbom.llm.FakeLlmClient;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmProperties;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

class ReportLlmRequestFactoryTest {

    private static final UUID USER_ID =
            UUID.fromString("7d273071-3ebf-4a0e-96e0-c6d9a623f799");
    private static final Instant FROM = ReportLlmTestFixtures.FROM;
    private static final Instant TO = ReportLlmTestFixtures.TO;

    @Test
    void readsOneTopImageWhenImagePolicyIsApproved() {
        DefectReportImageService imageService =
                mock(DefectReportImageService.class);
        when(imageService.findReportImages(
                USER_ID,
                FROM,
                TO,
                1))
                .thenReturn(List.of(image(
                        ReportLlmTestFixtures.DEFECT_ID,
                        "INC-20260715-000001",
                        1)));
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(imageService),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                USER_ID,
                prepared());

        verify(imageService).findReportImages(
                USER_ID,
                FROM,
                TO,
                1);
        assertThat(request.images()).hasSize(1);
        assertThat(request.images().getFirst().incidentCode())
                .isEqualTo("INC-20260715-000001");
    }

    @Test
    void emptyImageResultKeepsTextOnlyGeneration() {
        DefectReportImageService imageService =
                mock(DefectReportImageService.class);
        when(imageService.findReportImages(
                USER_ID,
                FROM,
                TO,
                1))
                .thenReturn(List.of());
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(imageService),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                USER_ID,
                prepared());

        assertThat(request.images()).isEmpty();
        assertThat(request.input())
                .containsKey("period");
    }

    @Test
    void unapprovedImagePolicyDoesNotReadOrSendImages() {
        DefectReportImageService imageService =
                mock(DefectReportImageService.class);
        LlmProperties properties = imageApprovedProperties();
        properties.setImageDeidentificationApproved(false);
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(imageService),
                        properties,
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                USER_ID,
                prepared());

        verifyNoInteractions(imageService);
        assertThat(request.images()).isEmpty();
    }

    @Test
    void missingImageServiceBeanKeepsTextOnlyGeneration() {
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.empty(),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        assertThat(factory.create(
                USER_ID,
                prepared()).images()).isEmpty();
    }

    @Test
    void unavailableLlmDoesNotReadImages() {
        DefectReportImageService imageService =
                mock(DefectReportImageService.class);
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(imageService),
                        imageApprovedProperties(),
                        new FakeLlmClient().available(false));

        LlmGenerationRequest request = factory.create(
                USER_ID,
                prepared());

        verifyNoInteractions(imageService);
        assertThat(request.images()).isEmpty();
    }

    @Test
    void mismatchedImageClassificationIsNotSent() {
        DefectReportImageService imageService =
                mock(DefectReportImageService.class);
        when(imageService.findReportImages(
                USER_ID,
                FROM,
                TO,
                1))
                .thenReturn(List.of(image(
                        ReportLlmTestFixtures.DEFECT_ID,
                        "INC-20260715-000001",
                        1,
                        "DAMAGED",
                        "CRITICAL")));
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(imageService),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        assertThat(factory.create(
                USER_ID,
                prepared()).images()).isEmpty();
    }

    private static LlmProperties imageApprovedProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setImageInputEnabled(true);
        properties.setImageDeidentificationApproved(true);
        return properties;
    }

    private static PreparedReportLlmInput prepared() {
        return new ReportLlmInputPreparer(
                new ObjectMapper().findAndRegisterModules())
                .prepare(ReportLlmTestFixtures.input());
    }

    private static DefectReportImage image(
            UUID defectId,
            String incidentCode,
            int marker
    ) {
        return image(
                defectId,
                incidentCode,
                marker,
                "DAMAGED",
                "HIGH");
    }

    private static DefectReportImage image(
            UUID defectId,
            String incidentCode,
            int marker,
            String defectType,
            String riskLevel
    ) {
        return new DefectReportImage(
                defectId,
                incidentCode,
                defectType,
                riskLevel,
                new byte[] {
                        (byte) 0xff,
                        (byte) 0xd8,
                        (byte) marker,
                        (byte) 0xff,
                        (byte) 0xd9
                },
                "image/jpeg",
                640,
                480);
    }
}
