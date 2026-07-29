package com.gilbom.statistics;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatisticsRepository {

    private static final String PATROL_AREA_EXISTS_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM robot.patrol_area
                WHERE patrol_area_id = :patrolAreaId
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StatisticsRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean patrolAreaExists(UUID patrolAreaId) {
        Boolean exists = jdbcTemplate.queryForObject(
                PATROL_AREA_EXISTS_SQL,
                new MapSqlParameterSource()
                        .addValue("patrolAreaId", patrolAreaId),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }
}
