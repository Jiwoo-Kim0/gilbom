package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.llm.LlmOutboundInputGuard;

/**
 * 타입 기반 allowlist DTO를 JSON slot으로 바꾸고 outbound guard를 통과시킨다.
 */
@Component
public class ReportLlmInputPreparer {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final LlmOutboundInputGuard outboundGuard;

    public ReportLlmInputPreparer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        outboundGuard = new LlmOutboundInputGuard(objectMapper);
    }

    public PreparedReportLlmInput prepare(ReportLlmInput input) {
        Map<String, Object> serialized =
                objectMapper.convertValue(input, JSON_OBJECT);
        Map<String, Object> promptInput =
                java.util.Collections.unmodifiableMap(
                        new LinkedHashMap<>(serialized));
        outboundGuard.validate(promptInput);
        Map<UUID, ReportLlmInput.DefectEvidence> evidenceById =
                input.defectEvidence().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                ReportLlmInput.DefectEvidence::defectId,
                                Function.identity()));
        return new PreparedReportLlmInput(
                input,
                promptInput,
                evidenceById);
    }

    public record PreparedReportLlmInput(
            ReportLlmInput source,
            Map<String, Object> promptInput,
            Map<UUID, ReportLlmInput.DefectEvidence> evidenceById
    ) {
    }
}
