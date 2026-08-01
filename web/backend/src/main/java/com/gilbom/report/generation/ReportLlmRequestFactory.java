package com.gilbom.report.generation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.gilbom.defect.DefectReportImageService;
import com.gilbom.defect.DefectReportImageService.DefectReportImage;
import com.gilbom.llm.LlmClient;
import com.gilbom.llm.LlmGenerationRequest;
import com.gilbom.llm.LlmImageContent;
import com.gilbom.llm.LlmProperties;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

/**
 * 보고서 입력과 선택적 이미지 근거를 외부 LLM 요청으로 변환한다.
 */
@Component
public class ReportLlmRequestFactory {

    private static final int CURRENT_MAX_IMAGES = 1;

    private final Optional<DefectReportImageService> imageService;
    private final LlmProperties properties;
    private final LlmClient llmClient;

    public ReportLlmRequestFactory(
            Optional<DefectReportImageService> imageService,
            LlmProperties properties,
            LlmClient llmClient
    ) {
        this.imageService = imageService;
        this.properties = properties;
        this.llmClient = llmClient;
    }

    /**
     * 타입 allowlist를 통과한 값과 사용자 접근 범위 안의 이미지 identity를 함께
     * 검증한다. 서비스가 다른 결함의 이미지를 반환하면 bytes를 보내지 않는다.
     */
    public LlmGenerationRequest create(
            UUID actorUserId,
            PreparedReportLlmInput prepared
    ) {
        if (actorUserId == null || prepared == null) {
            throw invalid();
        }
        ReportLlmInput.Period period =
                prepared.source().period();
        if (!llmClient.isAvailable()
                || !properties.isImageInputAllowed()
                || imageService.isEmpty()) {
            return new LlmGenerationRequest(
                    prepared.promptInput());
        }
        // 이미지는 근거 결함에서 직접 고른다. 기간으로 따로 고르면 근거와 다른 결함의
        // 이미지가 나와 아래 matchesEvidence에서 걸러지고, 결과적으로 이미지 없는
        // 보고서가 조용히 만들어진다. 결함 한 건짜리 보고서에서 특히 그렇다.
        // defectEvidence는 위험도 순으로 정렬되어 있다.
        List<LlmImageContent> images = prepared.source()
                .defectEvidence()
                .stream()
                .limit(CURRENT_MAX_IMAGES)
                .flatMap(evidence -> imageService.get()
                        .findReportImagesForDefect(
                                actorUserId,
                                evidence.defectId())
                        .stream())
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
            DefectReportImage image,
            PreparedReportLlmInput prepared
    ) {
        if (image == null) {
            return false;
        }
        ReportLlmInput.DefectEvidence evidence =
                prepared.evidenceById().get(image.defectId());
        return evidence != null
                && evidence.incidentCode().equals(
                        image.incidentCode())
                && evidence.defectType().name().equals(
                        image.defectType())
                && evidence.riskLevel().name().equals(
                        image.riskLevel());
    }

    private static LlmImageContent toLlmImage(
            DefectReportImage image
    ) {
        return new LlmImageContent(
                image.defectId(),
                image.incidentCode(),
                image.content(),
                image.contentType(),
                image.width(),
                image.height());
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.REPORT_VALIDATION_FAILED);
    }
}
