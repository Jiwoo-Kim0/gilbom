package com.gilbom.report.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gilbom.llm.FakeLlmClient;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmProperties;
import com.gilbom.report.generation.DefectImageContentReader.ImageContent;
import com.gilbom.report.generation.DefectImageContentReader.ReportPeriod;

class ReportLlmRequestFactoryTest {

    private static final Instant FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO =
            Instant.parse("2026-07-31T23:59:59Z");

    @Test
    void readsOneTopImageWhenImagePolicyIsApproved() {
        FakeReader reader = new FakeReader(List.of(
                image("INC-001", 1),
                image("INC-002", 2)));
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(reader),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                Map.of("statistics", Map.of("defectCount", 2)),
                FROM,
                TO);

        assertThat(reader.maxImages()).containsExactly(1);
        assertThat(reader.periods())
                .containsExactly(new ReportPeriod(FROM, TO));
        assertThat(request.images()).hasSize(1);
        assertThat(request.images().getFirst().incidentCode())
                .isEqualTo("INC-001");
    }

    @Test
    void emptyReaderResultKeepsTextOnlyGeneration() {
        FakeReader reader = new FakeReader(List.of());
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(reader),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                Map.of("period", "2026-07"),
                FROM,
                TO);

        assertThat(request.images()).isEmpty();
        assertThat(request.input())
                .containsEntry("period", "2026-07");
    }

    @Test
    void unapprovedImagePolicyDoesNotReadOrSendImages() {
        FakeReader reader = new FakeReader(
                List.of(image("INC-001", 1)));
        LlmProperties properties = imageApprovedProperties();
        properties.setImageDeidentificationApproved(false);
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(reader),
                        properties,
                        new FakeLlmClient());

        LlmGenerationRequest request = factory.create(
                Map.of("period", "2026-07"),
                FROM,
                TO);

        assertThat(reader.periods()).isEmpty();
        assertThat(request.images()).isEmpty();
    }

    @Test
    void missingReaderBeanKeepsTextOnlyGeneration() {
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.empty(),
                        imageApprovedProperties(),
                        new FakeLlmClient());

        assertThat(factory.create(
                Map.of("period", "2026-07"),
                FROM,
                TO).images()).isEmpty();
    }

    @Test
    void unavailableLlmDoesNotReadImages() {
        FakeReader reader = new FakeReader(
                List.of(image("INC-001", 1)));
        ReportLlmRequestFactory factory =
                new ReportLlmRequestFactory(
                        Optional.of(reader),
                        imageApprovedProperties(),
                        new FakeLlmClient().available(false));

        LlmGenerationRequest request = factory.create(
                Map.of("period", "2026-07"),
                FROM,
                TO);

        assertThat(reader.periods()).isEmpty();
        assertThat(request.images()).isEmpty();
    }

    private static LlmProperties imageApprovedProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setImageInputEnabled(true);
        properties.setImageDeidentificationApproved(true);
        return properties;
    }

    private static ImageContent image(
            String incidentCode,
            int marker
    ) {
        return new ImageContent(
                UUID.randomUUID(),
                incidentCode,
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

    private static final class FakeReader
            implements DefectImageContentReader {

        private final List<ImageContent> result;
        private final List<ReportPeriod> periods =
                new ArrayList<>();
        private final List<Integer> maxImages =
                new ArrayList<>();

        private FakeReader(List<ImageContent> result) {
            this.result = result;
        }

        @Override
        public List<ImageContent> readTopDefectImages(
                ReportPeriod period,
                int maximum
        ) {
            periods.add(period);
            maxImages.add(maximum);
            return result;
        }

        private List<ReportPeriod> periods() {
            return List.copyOf(periods);
        }

        private List<Integer> maxImages() {
            return List.copyOf(maxImages);
        }
    }
}
