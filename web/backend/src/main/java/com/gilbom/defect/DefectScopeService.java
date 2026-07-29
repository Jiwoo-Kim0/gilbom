package com.gilbom.defect;

import com.gilbom.robot.application.RobotScopeService;

/**
 * authorization-matrix.md 11장의 결함 객체 범위를 SQL 조건절로 제공한다.
 *
 * <p>결함은 로봇을 직접 가리키지 않고 관측(defect_observation)을 통해 로봇에서
 * 파생되므로, 배정 범위는 "이 결함에 범위 안 로봇의 관측이 하나라도 있는가"로
 * 판정한다. 전체 행을 읽고 Java에서 걸러내지 않고 SQL EXISTS로만 좁힌다.
 *
 * <p>시스템 ADMIN은 전체 객체 범위를 가지므로 관측 EXISTS와 무관하게 먼저
 * 통과시킨다. 그렇지 않으면 관측이 아직 없는 결함이 ADMIN에게도 보이지 않는다.
 *
 * <p>병합된 결함의 관측은 원본 결함에 그대로 남으므로(V12는
 * defect_observation에 UPDATE 권한을 주지 않는다) 대표 결함의 범위 판정에는
 * 자신에게 병합된 결함들의 관측까지 포함한다.
 */
public final class DefectScopeService {

    /**
     * 결함별 관측을 훑는 EXISTS의 앞부분. 로봇 범위 조건절이 뒤에 붙는다.
     */
    private static final String OBSERVATION_SCOPE_PREFIX = """
                OR EXISTS (
                    SELECT 1
                    FROM defect.defect_observation scope_observation
                    JOIN robot.robot r
                      ON r.robot_id = scope_observation.robot_id
                    WHERE (
                            scope_observation.defect_id = d.defect_id
                            OR scope_observation.defect_id IN (
                                SELECT merged.defect_id
                                FROM defect.defect merged
                                WHERE merged.merged_into_defect_id = d.defect_id
                            )
                        )
                      AND
            """;

    private static final String SCOPE_SUFFIX = """
                )
            )
            """;

    /**
     * 조회 범위. 결함 테이블을 {@code d}로 alias 하고 {@code userId}를 바인딩한
     * 질의에서 사용한다.
     */
    public static final String VISIBLE_DEFECT_PREDICATE = """
            (
            """
            + RobotScopeService.SYSTEM_ADMIN_SCOPE
            + OBSERVATION_SCOPE_PREFIX
            + RobotScopeService.VISIBLE_ROBOT_PREDICATE
            + SCOPE_SUFFIX;

    /**
     * 상태 변경·병합 범위. OPERATOR 또는 OWNER 배정을 요구한다.
     */
    static final String OPERABLE_DEFECT_PREDICATE = """
            (
            """
            + RobotScopeService.SYSTEM_ADMIN_SCOPE
            + OBSERVATION_SCOPE_PREFIX
            + RobotScopeService.OPERABLE_ROBOT_PREDICATE
            + SCOPE_SUFFIX;

    private DefectScopeService() {
    }
}
