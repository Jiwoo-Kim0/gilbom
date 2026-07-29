package com.gilbom.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

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
}
