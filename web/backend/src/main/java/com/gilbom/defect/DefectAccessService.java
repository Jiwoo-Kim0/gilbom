package com.gilbom.defect;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결함 단건에 대한 접근 가능 여부를 판정한다.
 *
 * <p>컬렉션 조회는 범위 조건을 질의 안에 넣어야 count·페이지네이션이 맞으므로
 * {@link DefectQueryService}가 조건절을 직접 사용한다. 반면 SSE처럼 "이 이벤트를 이
 * 사용자에게 보내도 되는가"를 건별로 묻는 곳은 조건절이 아니라 boolean이 필요하다.
 * {@code RobotScopeService.canView}와 같은 역할이다.
 */
@Service
public class DefectAccessService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DefectAccessService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public boolean canView(UUID userId, UUID defectId) {
        return exists(userId, defectId, DefectScopeService.VISIBLE_DEFECT_PREDICATE);
    }

    @Transactional(readOnly = true)
    public boolean canOperate(UUID userId, UUID defectId) {
        return exists(userId, defectId, DefectScopeService.OPERABLE_DEFECT_PREDICATE);
    }

    private boolean exists(UUID userId, UUID defectId, String scopePredicate) {
        if (userId == null || defectId == null) {
            return false;
        }
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM defect.defect d
                    WHERE d.defect_id = :defectId
                      AND
                """ + scopePredicate + """
                )
                """,
                Map.of("userId", userId, "defectId", defectId),
                Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }
}
