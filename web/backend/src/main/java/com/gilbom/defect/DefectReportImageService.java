package com.gilbom.defect;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.defect.DefectEvidenceImageLoader.LoadedImage;

/**
 * LLM 보고서 생성이 쓸 결함 근거 이미지를 고르고 바이트까지 실어 돌려준다.
 *
 * <p>보고서 한 건에 들어가는 이미지 수를 서버가 제한한다. 512KB JPEG는 base64로
 * 약 683KB이므로, 결함 건수만큼 넣으면 요청이 그대로 터진다. 상한을 클라이언트
 * 선의에 맡기지 않는 이유다.
 *
 * <p>여러 장을 보낼 때 어느 이미지가 어느 결함인지 모델이 알 수 있도록
 * {@code defectId}와 {@code incidentCode}를 함께 준다.
 */
@Service
public class DefectReportImageService {

    /**
     * 보고서 한 건이 실을 수 있는 최대 장수. 팀 합의값은 1장이며 이 상한은
     * 나중에 늘릴 때 계약을 다시 바꾸지 않기 위한 여유다.
     */
    public static final int MAX_IMAGES = 3;

    /** 호출부가 지정하지 않았을 때의 장수. A와 합의한 실제 사용량이다. */
    public static final int DEFAULT_IMAGES = 1;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DefectEvidenceImageLoader imageLoader;

    public DefectReportImageService(
            NamedParameterJdbcTemplate jdbcTemplate,
            DefectEvidenceImageLoader imageLoader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.imageLoader = imageLoader;
    }

    public List<DefectReportImage> findReportImages(
            UUID userId,
            Instant from,
            Instant to
    ) {
        return findReportImages(userId, from, to, DEFAULT_IMAGES);
    }

    /**
     * @param maxImages 1 이상 {@link #MAX_IMAGES} 이하. 범위를 벗어나면 상한으로 자른다
     * @return 0~maxImages개. 기간에 결함이 없거나 이미지를 읽지 못하면 빈 리스트이며,
     *         호출부는 이미지 없이 텍스트만으로 보고서를 만들어야 한다
     */
    @Transactional(readOnly = true)
    public List<DefectReportImage> findReportImages(
            UUID userId,
            Instant from,
            Instant to,
            int maxImages
    ) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHENTICATED);
        }
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED,
                    "보고서 기간이 올바르지 않습니다.");
        }

        List<ImageCandidate> candidates = findCandidates(
                userId,
                from,
                to,
                Math.clamp(maxImages, 1, MAX_IMAGES));

        List<DefectReportImage> images = new ArrayList<>(candidates.size());
        for (ImageCandidate candidate : candidates) {
            // 한 장을 읽지 못해도 나머지로 계속한다. 보고서가 이미지 때문에 실패하면 안 된다.
            imageLoader.load(candidate.imageId())
                    .map(loaded -> toReportImage(candidate, loaded))
                    .ifPresent(images::add);
        }
        return List.copyOf(images);
    }

    private static DefectReportImage toReportImage(
            ImageCandidate candidate,
            LoadedImage loaded
    ) {
        return new DefectReportImage(
                candidate.defectId(),
                candidate.incidentCode(),
                candidate.defectType(),
                candidate.riskLevel(),
                loaded.content(),
                loaded.contentType(),
                loaded.width(),
                loaded.height());
    }

    /**
     * 대표 결함 상위 N건과 각 결함의 대표 관측 이미지를 고른다.
     *
     * <p>정렬은 위험도 → 반복 관측 횟수 → 최근 순이다. 보고서에 실을 한 장은
     * "가장 심각하고 반복 확인된 결함"이어야 한다.
     *
     * <p>병합된 결함의 관측도 대표 결함의 후보에 포함한다. 결함 상세 화면이 쓰는
     * 선정 규칙과 같다.
     */
    private List<ImageCandidate> findCandidates(
            UUID userId,
            Instant from,
            Instant to,
            int limit
    ) {
        return jdbcTemplate.query("""
                SELECT
                    d.defect_id,
                    d.incident_code,
                    d.defect_type,
                    d.risk_level,
                    evidence.image_id
                FROM defect.defect d
                JOIN LATERAL (
                    SELECT observation.image_id
                    FROM defect.defect_observation observation
                    WHERE (
                            observation.defect_id = d.defect_id
                            OR observation.defect_id IN (
                                SELECT merged.defect_id
                                FROM defect.defect merged
                                WHERE merged.merged_into_defect_id = d.defect_id
                            )
                        )
                      AND observation.image_id IS NOT NULL
                    ORDER BY observation.is_representative DESC,
                             observation.confidence DESC,
                             observation.created_at DESC
                    LIMIT 1
                ) evidence ON TRUE
                WHERE d.merged_into_defect_id IS NULL
                  AND d.last_seen_at >= :from
                  AND d.last_seen_at < :to
                  AND
                """ + DefectScopeService.VISIBLE_DEFECT_PREDICATE + """
                ORDER BY
                    CASE d.risk_level
                        WHEN 'CRITICAL' THEN 0
                        WHEN 'HIGH' THEN 1
                        WHEN 'MEDIUM' THEN 2
                        ELSE 3
                    END,
                    d.observation_count DESC,
                    d.last_seen_at DESC,
                    d.defect_id ASC
                LIMIT :limit
                """,
                Map.of(
                        "userId", userId,
                        "from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                        "to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC),
                        "limit", limit),
                (resultSet, rowNumber) -> new ImageCandidate(
                        resultSet.getObject("defect_id", UUID.class),
                        resultSet.getString("incident_code"),
                        resultSet.getString("defect_type"),
                        resultSet.getString("risk_level"),
                        resultSet.getObject("image_id", UUID.class)));
    }

    /**
     * LLM 요청에 실을 결함 이미지 한 장. base64 인코딩은 호출부가 수행한다.
     */
    public record DefectReportImage(
            UUID defectId,
            String incidentCode,
            String defectType,
            String riskLevel,
            byte[] content,
            String contentType,
            int width,
            int height
    ) {

        public DefectReportImage {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record ImageCandidate(
            UUID defectId,
            String incidentCode,
            String defectType,
            String riskLevel,
            UUID imageId
    ) {
    }
}
