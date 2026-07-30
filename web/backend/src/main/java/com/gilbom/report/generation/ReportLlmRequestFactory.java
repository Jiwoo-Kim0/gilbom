package com.gilbom.report.generation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gilbom.llm.LlmClient;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmImageContent;
import com.gilbom.llm.LlmProperties;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.generation.DefectImageContentReader.ImageContent;
import com.gilbom.report.generation.DefectImageContentReader.ReportPeriod;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

/**
 * 보고서 입력과 선택적 이미지 근거를 외부 LLM 요청으로 변환한다.
 */
@Component
public class ReportLlmRequestFactory {

    private static final int CURRENT_MAX_IMAGES = 1;

    private final Optional<DefectImageContentReader> imageReader;
    private final LlmProperties properties;
    private final LlmClient llmClient;

    public ReportLlmRequestFactory(
            Optional<DefectImageContentReader> imageReader,
            LlmProperties properties,
            LlmClient llmClient
    ) {
        this.imageReader = imageReader;
        this.properties = properties;
        this.llmClient = llmClient;
    }

    public LlmGenerationRequest create(
            Map<String, Object> input,
            Instant periodFrom,
            Instant periodTo
    ) {
        if (!llmClient.isAvailable()
                || !properties.isImageInputAllowed()
                || imageReader.isEmpty()) {
            return new LlmGenerationRequest(input);
        }
        List<ImageContent> selected =
                imageReader.get().readTopDefectImages(
                        new ReportPeriod(periodFrom, periodTo),
                        CURRENT_MAX_IMAGES);
        if (selected == null || selected.isEmpty()) {
            return new LlmGenerationRequest(input);
        }
        List<LlmImageContent> images = selected.stream()
                .limit(CURRENT_MAX_IMAGES)
                .map(ReportLlmRequestFactory::toLlmImage)
                .toList();
        return new LlmGenerationRequest(input, images);
    }

    /**
     * 실제 외부 호출 경로는 타입 allowlist를 통과한 값과 결함 identity를 함께
     * 검증한다. Reader가 다른 기간 또는 결함의 이미지를 반환하면 bytes를 보내지
     * 않고 생성 파이프라인이 안전한 템플릿으로 낮출 수 있도록 실패시킨다.
     */
    public LlmGenerationRequest create(
            PreparedReportLlmInput prepared
    ) {
        if (prepared == null) {
            throw invalid();
        }
        ReportLlmInput.Period period =
                prepared.source().period();
        if (!llmClient.isAvailable()
                || !properties.isImageInputAllowed()
                || imageReader.isEmpty()) {
            return new LlmGenerationRequest(
                    prepared.promptInput());
        }
        List<ImageContent> selected =
                imageReader.get().readTopDefectImages(
                        new ReportPeriod(
                                period.from(),
                                period.to()),
                        CURRENT_MAX_IMAGES);
        if (selected == null || selected.isEmpty()) {
            return new LlmGenerationRequest(
                    prepared.promptInput());
        }
        List<LlmImageContent> images = selected.stream()
                .limit(CURRENT_MAX_IMAGES)
                .filter(image -> matchesEvidence(
                        image,
                        prepared))
                .map(ReportLlmRequestFactory::toLlmImage)
                .toList();
        if (images.isEmpty()) {
            return new LlmGenerationRequest(
                    prepared.promptInput());
        }
        return new LlmGenerationRequest(
                prepared.promptInput(),
                images);
    }

    private static boolean matchesEvidence(
            ImageContent image,
            PreparedReportLlmInput prepared
    ) {
        if (image == null) {
            return false;
        }
        ReportLlmInput.DefectEvidence evidence =
                prepared.evidenceById().get(image.defectId());
        return evidence != null
                && evidence.incidentCode().equals(
                        image.incidentCode());
    }

    private static LlmImageContent toLlmImage(
            ImageContent image
    ) {
        return new LlmImageContent(
                image.defectId(),
                image.incidentCode(),
                image.bytes(),
                image.contentType(),
                image.width(),
                image.height());
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
