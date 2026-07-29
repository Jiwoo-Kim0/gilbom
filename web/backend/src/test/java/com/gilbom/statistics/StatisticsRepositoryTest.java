package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.gilbom.statistics.StatisticsDtos.DefectType;
import com.gilbom.statistics.StatisticsDtos.DefectWorkflowStatus;

@ExtendWith(MockitoExtension.class)
class StatisticsRepositoryTest {

    private static final UUID PATROL_AREA_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void checksPatrolAreaExistenceWithNamedParameterBinding() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Boolean.class)))
                .thenReturn(true);
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);

        assertThat(repository.patrolAreaExists(PATROL_AREA_ID)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(
                sql.capture(),
                parameters.capture(),
                eq(Boolean.class));
        assertThat(sql.getValue())
                .contains("patrol_area_id = :patrolAreaId")
                .doesNotContain(PATROL_AREA_ID.toString());
        assertThat(parameters.getValue().getValue("patrolAreaId"))
                .isEqualTo(PATROL_AREA_ID);
    }

    @Test
    void treatsNullDatabaseResultAsNotExisting() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Boolean.class)))
                .thenReturn(null);
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);

        assertThat(repository.patrolAreaExists(PATROL_AREA_ID))
                .isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void groupsVisibleCurrentDefectsWithBoundPeriodParameters() {
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(
                        new StatisticsRepository.WorkflowStatusCount(
                                DefectWorkflowStatus.NEW,
                                2)));
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);
        StatisticsPeriod period = new StatisticsPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-05T00:00:00Z"));

        assertThat(repository.countDefectsByWorkflowStatus(
                PATROL_AREA_ID,
                period))
                .containsExactly(
                        new StatisticsRepository.WorkflowStatusCount(
                                DefectWorkflowStatus.NEW,
                                2));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                parameters.capture(),
                any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("GROUP BY d.workflow_status")
                .contains("auth.user_robot_assignment")
                .contains("d.merged_into_defect_id IS NULL")
                .contains("d.last_seen_at >= :from")
                .contains("d.last_seen_at < :to")
                .doesNotContain(PATROL_AREA_ID.toString())
                .doesNotContain(period.from().toString())
                .doesNotContain(period.to().toString());
        assertThat(parameters.getValue().getValue("userId"))
                .isEqualTo(PATROL_AREA_ID);
        assertThat(parameters.getValue().getValue("from"))
                .isEqualTo(period.from().atOffset(ZoneOffset.UTC));
        assertThat(parameters.getValue().getValue("to"))
                .isEqualTo(period.to().atOffset(ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    @Test
    void groupsDistinctVisibleCurrentDefectsByType() {
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(
                        new StatisticsRepository.DefectTypeCount(
                                DefectType.DAMAGED,
                                2)));
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);
        StatisticsPeriod period = new StatisticsPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-05T00:00:00Z"));

        assertThat(repository.countDefectsByType(
                PATROL_AREA_ID,
                period))
                .containsExactly(
                        new StatisticsRepository.DefectTypeCount(
                                DefectType.DAMAGED,
                                2));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                parameters.capture(),
                any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("COUNT(DISTINCT d.defect_id)")
                .contains("GROUP BY d.defect_type")
                .contains("auth.user_robot_assignment")
                .contains("d.merged_into_defect_id IS NULL")
                .contains("d.last_seen_at >= :from")
                .contains("d.last_seen_at < :to")
                .doesNotContain(PATROL_AREA_ID.toString())
                .doesNotContain(period.from().toString())
                .doesNotContain(period.to().toString());
        assertThat(parameters.getValue().getValue("userId"))
                .isEqualTo(PATROL_AREA_ID);
        assertThat(parameters.getValue().getValue("from"))
                .isEqualTo(period.from().atOffset(ZoneOffset.UTC));
        assertThat(parameters.getValue().getValue("to"))
                .isEqualTo(period.to().atOffset(ZoneOffset.UTC));
    }

    @Test
    void countsFilteredAreasWithNamedParameterBinding() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                eq(Long.class)))
                .thenReturn(1L);
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);

        assertThat(repository.countPatrolAreas(PATROL_AREA_ID))
                .isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(
                sql.capture(),
                parameters.capture(),
                eq(Long.class));
        assertThat(sql.getValue())
                .contains("CAST(:patrolAreaId AS uuid)")
                .doesNotContain(PATROL_AREA_ID.toString());
        assertThat(parameters.getValue().getValue("patrolAreaId"))
                .isEqualTo(PATROL_AREA_ID);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aggregatesVisibleSourcesInSeparateCtesBeforeJoiningAreas() {
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(new StatisticsRepository.AreaStatisticsRow(
                        PATROL_AREA_ID,
                        "A-11",
                        "Fixture Area A",
                        2, 1, 1, 0,
                        5_400, 3, 2, 3)));
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);
        StatisticsQuery.ByArea query = StatisticsQuery.byArea(
                new StatisticsPeriod(
                        Instant.parse("2026-07-01T00:00:00Z"),
                        Instant.parse("2026-07-05T00:00:00Z")),
                null,
                2,
                25);

        assertThat(repository.findAreaStatistics(
                PATROL_AREA_ID,
                query))
                .singleElement()
                .extracting(row -> row.areaCode())
                .isEqualTo("A-11");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                parameters.capture(),
                any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("visible_mission_source AS")
                .contains("mission_aggregate AS")
                .contains("FROM visible_mission_source")
                .contains("visible_incident_source AS")
                .contains("incident_aggregate AS")
                .contains("FROM visible_incident_source")
                .contains("visible_defect_source AS")
                .contains("defect_aggregate AS")
                .contains("FROM visible_defect_source")
                .contains("visible_observation_source AS")
                .contains("observation_aggregate AS")
                .contains("FROM visible_observation_source")
                .contains("auth.user_robot_assignment")
                .contains("LEFT JOIN mission_aggregate")
                .contains("LEFT JOIN incident_aggregate")
                .contains("LEFT JOIN defect_aggregate")
                .contains("LEFT JOIN observation_aggregate")
                .contains("ORDER BY area.area_code, area.patrol_area_id")
                .contains("LIMIT :size")
                .contains("OFFSET :offset");
        assertThat(parameters.getValue().getValue("userId"))
                .isEqualTo(PATROL_AREA_ID);
        assertThat(parameters.getValue().getValue("patrolAreaId"))
                .isNull();
        assertThat(parameters.getValue().getValue("size"))
                .isEqualTo(25);
        assertThat(parameters.getValue().getValue("offset"))
                .isEqualTo(50L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void generatesUtcDayBucketsAndAggregatesEachVisibleSourceSeparately() {
        when(jdbcTemplate.query(
                anyString(),
                any(MapSqlParameterSource.class),
                any(RowMapper.class)))
                .thenReturn(List.of(
                        new StatisticsRepository.PeriodStatisticsRow(
                                LocalDate.of(2026, 7, 1),
                                1, 1, 0, 0,
                                3_600, 2, 1, 2)));
        StatisticsRepository repository =
                new StatisticsRepository(jdbcTemplate);
        StatisticsPeriod period = new StatisticsPeriod(
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"));
        StatisticsQuery.ByPeriod query =
                StatisticsQuery.byPeriod(period, "DAY");

        assertThat(repository.findPeriodStatistics(
                PATROL_AREA_ID,
                query))
                .singleElement()
                .extracting(row -> row.date())
                .isEqualTo(LocalDate.of(2026, 7, 1));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                parameters.capture(),
                any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("generate_series(")
                .contains("AT TIME ZONE 'UTC'")
                .contains("visible_mission_source AS")
                .contains("mission_aggregate AS")
                .contains("visible_incident_source AS")
                .contains("incident_aggregate AS")
                .contains("visible_defect_source AS")
                .contains("defect_aggregate AS")
                .contains("visible_observation_source AS")
                .contains("observation_aggregate AS")
                .contains("mission.started_at >= :from")
                .contains("mission.started_at < :to")
                .contains("event.occurred_at >= :from")
                .contains("event.occurred_at < :to")
                .contains("d.last_seen_at >= :from")
                .contains("d.last_seen_at < :to")
                .contains("observation.created_at >= :from")
                .contains("observation.created_at < :to")
                .contains("auth.user_robot_assignment")
                .contains("LEFT JOIN mission_aggregate")
                .contains("LEFT JOIN incident_aggregate")
                .contains("LEFT JOIN defect_aggregate")
                .contains("LEFT JOIN observation_aggregate")
                .contains("ORDER BY bucket.bucket_date ASC")
                .doesNotContain(period.from().toString())
                .doesNotContain(period.to().toString());
        assertThat(parameters.getValue().getValue("userId"))
                .isEqualTo(PATROL_AREA_ID);
        assertThat(parameters.getValue().getValue("from"))
                .isEqualTo(period.from().atOffset(ZoneOffset.UTC));
        assertThat(parameters.getValue().getValue("to"))
                .isEqualTo(period.to().atOffset(ZoneOffset.UTC));
    }
}
