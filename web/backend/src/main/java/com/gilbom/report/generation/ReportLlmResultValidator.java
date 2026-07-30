package com.gilbom.report.generation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.llm.LlmGenerationResponse;
import com.gilbom.report.generation.ReportLlmInput.DefectEvidence;
import com.gilbom.report.generation.ReportLlmInput.DefectStatusCount;
import com.gilbom.report.generation.ReportLlmInput.DefectTypeCount;
import com.gilbom.report.generation.ReportLlmInputPreparer.PreparedReportLlmInput;

/**
 * JSON Schema 형태와 서버 원본 snapshot을 모두 검증한다.
 */
@Component
public class ReportLlmResultValidator {

    public static final int MAXIMUM_RESPONSE_CHARACTERS =
            100_000;

    private final ObjectMapper objectMapper;
    private final GeneratedReportTextGuard textGuard;

    public ReportLlmResultValidator(
            ObjectMapper objectMapper,
            GeneratedReportTextGuard textGuard
    ) {
        this.objectMapper = objectMapper.copy()
                .enable(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES);
        this.textGuard = textGuard;
    }

    public ReportLlmResult validate(
            LlmGenerationResponse response,
            PreparedReportLlmInput prepared
    ) {
        if (response == null
                || prepared == null
                || response.content().length()
                        > MAXIMUM_RESPONSE_CHARACTERS) {
            throw invalid();
        }
        try {
            JsonNode root = objectMapper.readTree(
                    response.content());
            validateSchemaShape(root);
            ReportLlmResult result = objectMapper.treeToValue(
                    root,
                    ReportLlmResult.class);
            validateMetadata(response, prepared, result);
            validateStatistics(prepared.source(), result);
            validateEvidence(prepared, result);
            validateGeneratedText(result);
            return result;
        } catch (BusinessException exception) {
            if (exception.errorCode()
                    == ErrorCode.LLM_RESPONSE_INVALID) {
                throw exception;
            }
            throw invalid();
        } catch (JsonProcessingException
                | IllegalArgumentException
                | NullPointerException exception) {
            // provider 원문과 parser 메시지를 cause로 보존하지 않는다.
            throw invalid();
        }
    }

    private static void validateSchemaShape(JsonNode root) {
        requireObjectFields(
                root,
                Set.of(
                        "title",
                        "summary",
                        "statistics",
                        "keyDefects",
                        "actionRecommendations",
                        "period",
                        "promptVersion",
                        "modelIdentifier"),
                Set.of());
        JsonNode statistics = root.get("statistics");
        requireObjectFields(
                statistics,
                Set.of(
                        "aggregate",
                        "defectsByStatus",
                        "defectsByType"),
                Set.of());
        requireObjectFields(
                statistics.get("aggregate"),
                Set.of(
                        "missionCount",
                        "completedMissionCount",
                        "cancelledMissionCount",
                        "failedMissionCount",
                        "completionRatePercent",
                        "totalMissionDurationSeconds",
                        "incidentEventCount",
                        "defectCount",
                        "observationCount"),
                Set.of());
        requireArray(
                statistics.get("defectsByStatus"),
                6,
                Set.of("workflowStatus", "defectCount"),
                Set.of());
        requireArray(
                statistics.get("defectsByType"),
                7,
                Set.of("defectType", "defectCount"),
                Set.of());
        requireArray(
                root.get("keyDefects"),
                ReportLlmResult.MAXIMUM_ITEMS,
                Set.of(
                        "defectId",
                        "incidentCode",
                        "defectType",
                        "riskLevel",
                        "lastSeenAt",
                        "rationale"),
                Set.of("locationLabel"));
        requireArray(
                root.get("actionRecommendations"),
                ReportLlmResult.MAXIMUM_ITEMS,
                Set.of(
                        "priority",
                        "recommendation",
                        "rationale",
                        "relatedDefectIds"),
                Set.of());
        for (JsonNode recommendation
                : root.get("actionRecommendations")) {
            JsonNode identifiers =
                    recommendation.get("relatedDefectIds");
            if (identifiers == null
                    || !identifiers.isArray()
                    || identifiers.size()
                            > ReportLlmResult.MAXIMUM_ITEMS) {
                throw invalid();
            }
        }
        requireObjectFields(
                root.get("period"),
                Set.of("from", "to"),
                Set.of());
    }

    private static void validateMetadata(
            LlmGenerationResponse response,
            PreparedReportLlmInput prepared,
            ReportLlmResult result
    ) {
        if (!response.modelIdentifier().equals(
                result.modelIdentifier())
                || !response.promptVersion().equals(
                        result.promptVersion())
                || !prepared.source().period().from().equals(
                        result.period().from())
                || !prepared.source().period().to().equals(
                        result.period().to())) {
            throw invalid();
        }
    }

    private static void validateStatistics(
            ReportLlmInput input,
            ReportLlmResult result
    ) {
        ReportLlmInput.Aggregate expected =
                input.statistics().aggregate();
        ReportLlmInput.Aggregate actual =
                result.statistics().aggregate();
        if (expected.missionCount() != actual.missionCount()
                || expected.completedMissionCount()
                        != actual.completedMissionCount()
                || expected.cancelledMissionCount()
                        != actual.cancelledMissionCount()
                || expected.failedMissionCount()
                        != actual.failedMissionCount()
                || expected.totalMissionDurationSeconds()
                        != actual.totalMissionDurationSeconds()
                || expected.incidentEventCount()
                        != actual.incidentEventCount()
                || expected.defectCount()
                        != actual.defectCount()
                || expected.observationCount()
                        != actual.observationCount()
                || compare(
                        expected.completionRatePercent(),
                        actual.completionRatePercent()) != 0
                || !countsByStatus(
                        input.statistics().defectsByStatus())
                        .equals(countsByStatus(
                                result.statistics()
                                        .defectsByStatus()))
                || !countsByType(
                        input.statistics().defectsByType())
                        .equals(countsByType(
                                result.statistics()
                                        .defectsByType()))) {
            throw invalid();
        }
    }

    private static void validateEvidence(
            PreparedReportLlmInput prepared,
            ReportLlmResult result
    ) {
        Set<UUID> seen = new HashSet<>();
        for (ReportLlmResult.KeyDefect keyDefect
                : result.keyDefects()) {
            DefectEvidence source = prepared.evidenceById()
                    .get(keyDefect.defectId());
            if (source == null
                    || !seen.add(keyDefect.defectId())
                    || !source.incidentCode().equals(
                            keyDefect.incidentCode())
                    || source.defectType()
                            != keyDefect.defectType()
                    || source.riskLevel()
                            != keyDefect.riskLevel()
                    || !source.lastSeenAt().equals(
                            keyDefect.lastSeenAt())
                    || !java.util.Objects.equals(
                            source.locationLabel(),
                            keyDefect.locationLabel())) {
                throw invalid();
            }
        }
        for (ReportLlmResult.ActionRecommendation recommendation
                : result.actionRecommendations()) {
            if (!prepared.evidenceById().keySet().containsAll(
                    recommendation.relatedDefectIds())) {
                throw invalid();
            }
        }
    }

    private void validateGeneratedText(ReportLlmResult result) {
        textGuard.validate(result.title());
        textGuard.validate(result.summary());
        for (ReportLlmResult.KeyDefect defect
                : result.keyDefects()) {
            textGuard.validate(defect.incidentCode());
            textGuard.validate(defect.rationale());
            if (defect.locationLabel() != null) {
                textGuard.validate(defect.locationLabel());
            }
        }
        for (ReportLlmResult.ActionRecommendation recommendation
                : result.actionRecommendations()) {
            textGuard.validate(
                    recommendation.recommendation());
            textGuard.validate(recommendation.rationale());
        }
    }

    private static Map<Object, Long> countsByStatus(
            java.util.List<DefectStatusCount> values
    ) {
        return counts(
                values,
                DefectStatusCount::workflowStatus,
                DefectStatusCount::defectCount);
    }

    private static Map<Object, Long> countsByType(
            java.util.List<DefectTypeCount> values
    ) {
        return counts(
                values,
                DefectTypeCount::defectType,
                DefectTypeCount::defectCount);
    }

    private static <T> Map<Object, Long> counts(
            java.util.List<T> values,
            Function<T, Object> key,
            java.util.function.ToLongFunction<T> count
    ) {
        Map<Object, Long> mapped = new HashMap<>();
        for (T value : values) {
            if (mapped.put(key.apply(value), count.applyAsLong(value))
                    != null) {
                throw invalid();
            }
        }
        return Map.copyOf(mapped);
    }

    private static int compare(
            BigDecimal left,
            BigDecimal right
    ) {
        return left.compareTo(right);
    }

    private static void requireArray(
            JsonNode node,
            int maximumItems,
            Set<String> required,
            Set<String> optional
    ) {
        if (node == null
                || !node.isArray()
                || node.size() > maximumItems) {
            throw invalid();
        }
        for (JsonNode item : node) {
            requireObjectFields(item, required, optional);
        }
    }

    private static void requireObjectFields(
            JsonNode node,
            Set<String> required,
            Set<String> optional
    ) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = node.fieldNames();
        fields.forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required)
                || !allowed.containsAll(actual)) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.LLM_RESPONSE_INVALID);
    }
}
