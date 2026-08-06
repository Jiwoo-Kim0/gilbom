package com.gilbom.robot.mqtt.receipt;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IncidentReceiptProjectionReader {

    private final JdbcTemplate jdbcTemplate;

    public IncidentReceiptProjectionReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IncidentReceiptProjection findByEventId(UUID eventId) {
        return jdbcTemplate.query("""
                        SELECT
                            d.incident_code,
                            o.is_representative
                        FROM defect.defect_observation o
                        JOIN defect.defect d
                          ON d.defect_id = o.defect_id
                        WHERE o.event_id = ?
                        """,
                resultSet -> resultSet.next()
                        ? new IncidentReceiptProjection(
                                resultSet.getString("incident_code"),
                                resultSet.getBoolean("is_representative"))
                        : null,
                eventId);
    }

    public record IncidentReceiptProjection(
            String incidentId,
            boolean representative
    ) {
    }
}
