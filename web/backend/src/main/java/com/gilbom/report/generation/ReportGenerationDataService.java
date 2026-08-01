package com.gilbom.report.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportDtos.LlmReportCreateRequest;
import com.gilbom.report.generation.ReportGenerationDataRepository.EvidenceJson;
import com.gilbom.report.generation.ReportGenerationDataRepository.Snapshot;

/**
 * HTTP 요청 값을 외부 DTO로 넘기지 않고 서버 조회 snapshot으로 교체한다.
 */
@Service
public class ReportGenerationDataService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT =
            new TypeReference<>() {
            };

    private final ReportGenerationDataRepository repository;
    private final ObjectMapper objectMapper;

    public ReportGenerationDataService(
            ReportGenerationDataRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public PreparedCreation prepare(
            UUID actorUserId,
            LlmReportCreateRequest request
    ) {
        ReportGenerationCriteria criteria =
                ReportGenerationCriteria.from(
                        request.filterCriteria());
        // LLM 보고서는 결함 한 건을 근거로만 만든다. 기간 전체를 근거로 삼는 형태는
        // 제품에서 쓰지 않으므로, 대상 결함 없이 들어오면 만들지 않고 거부한다.
        // 사람이 직접 쓰는 MANUAL·TEMPLATE 보고서는 이 경로를 타지 않는다.
        if (!criteria.isSingleDefect()) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED,
                    "LLM 보고서는 대상 결함을 지정해야 합니다.");
        }
        ReportLlmInput.Period period = new ReportLlmInput.Period(
                request.periodFrom(),
                request.periodTo());
        Snapshot snapshot = repository.load(
                actorUserId,
                period.from(),
                period.to(),
                criteria);
        ReportLlmInput input = new ReportLlmInput(
                period,
                new ReportLlmInput.Statistics(
                        snapshot.aggregate(),
                        snapshot.statusCounts(),
                        snapshot.typeCounts(),
                        snapshot.riskCounts()),
                snapshot.evidence());
        // 조회 결과에 그 결함이 없으면 근거가 0건인 보고서가 된다. 없는 결함,
        // 접근 범위 밖 결함, 기간 밖 결함이 모두 여기로 온다. 셋을 구분하지 않는
        // 것은 결함 조회(DEFECT_NOT_FOUND)와 같은 방침이다 — 존재 여부를 숨긴다.
        if (snapshot.evidence().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED,
                    "대상 결함을 찾을 수 없습니다.");
        }
        Map<String, Object> evidenceSnapshot =
                evidenceSnapshot(snapshot.evidenceMetadata());
        // 단건 보고서는 결함 코드를 제목에 넣어야 목록에서 구분된다.
        String incidentCode = snapshot.evidence().getFirst().incidentCode();
        String title = "%s 결함 보고서".formatted(incidentCode);
        String summary =
                "결함 %s 한 건에 대한 보고서입니다.".formatted(incidentCode);
        return new PreparedCreation(
                criteria.toSnapshot(),
                new ReportLlmPipelineRequest(
                        actorUserId,
                        request.reportType(),
                        title,
                        summary,
                        input,
                        evidenceSnapshot));
    }

    private Map<String, Object> evidenceSnapshot(
            List<EvidenceJson> values
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "defectIds",
                values.stream()
                        .map(EvidenceJson::defectId)
                        .distinct()
                        .map(UUID::toString)
                        .toList());
        result.put(
                "missionIds",
                values.stream()
                        .map(EvidenceJson::missionId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .map(UUID::toString)
                        .toList());
        result.put(
                "patrolAreaIds",
                values.stream()
                        .map(EvidenceJson::patrolAreaId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .map(UUID::toString)
                        .toList());
        return objectMapper.convertValue(result, JSON_OBJECT);
    }

    public record PreparedCreation(
            Map<String, Object> filterCriteria,
            ReportLlmPipelineRequest pipelineRequest
    ) {
    }
}
