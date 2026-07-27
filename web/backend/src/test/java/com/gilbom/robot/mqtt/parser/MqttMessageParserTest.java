package com.gilbom.robot.mqtt.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.client.MqttInboundMessage;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttMessageParserTest {

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-07-26T05:15:36Z");

    private final MqttMessageParser parser = createParser();

    @Test
    void parsesTelemetryIntoTypedV2Contract() {
        ParsedMqttMessage parsed = parser.parse(message(
                "tbs/v2/robots/RBT-001/telemetry",
                0,
                telemetryPayload("RBT-001", "2.0", """
                        "valid": true,
                        "lat": 37.4979,
                        "lon": 127.0276,
                        "heading_deg": 92.3
                        """)
        ));

        assertThat(parsed.payload()).isInstanceOf(TelemetryPayload.class);
        TelemetryPayload telemetry = (TelemetryPayload) parsed.payload();
        assertThat(telemetry.messageId().toString())
                .isEqualTo("fb9c4472-226d-4473-b684-f0d8282292bd");
        assertThat(telemetry.position().headingDeg()).isEqualTo(92.3);
        assertThat(telemetry.system().jetsonTempC()).isEqualTo(41.5);
        assertThat(parsed.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void parsesCommandRequestAndValidatesItsParameters() {
        String payload = """
                {
                  "schema_version": "2.0",
                  "command_id": "a75d9cec-b04d-4f66-b18a-4c48782f805a",
                  "command_type": "START_MISSION",
                  "issued_at": "2026-07-26T05:30:00Z",
                  "expires_at": "2026-07-26T05:31:00Z",
                  "requested_by": {
                    "actor_type": "ADMIN",
                    "actor_id": "ADM-0042"
                  },
                  "parameters": {
                    "mission_id": "MIS-20260726-001",
                    "zone_id": "A-01"
                  }
                }
                """;

        ParsedMqttMessage parsed = parser.parse(message(
                "tbs/v2/robots/RBT-001/commands/request",
                1,
                payload
        ));

        assertThat(parsed.payload()).isInstanceOf(CommandRequestPayload.class);
        CommandRequestPayload command = (CommandRequestPayload) parsed.payload();
        assertThat(command.parameters().missionId())
                .isEqualTo("MIS-20260726-001");
    }

    @Test
    void acceptsIncidentWithoutConditionalAttributes() {
        String payload = """
                {
                  "schema_version": "2.0",
                  "event_id": "3cf98dc0-424a-4ef0-9423-57e53fcd72fc",
                  "event_type": "BRAILLE_BLOCK_INCIDENT_DETECTED",
                  "robot_id": "RBT-001",
                  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
                  "seq": 1482,
                  "source_component": "ai",
                  "occurred_at": "2026-07-26T05:15:34.231Z",
                  "sent_at": "2026-07-26T05:15:35.002Z",
                  "mission_id": null,
                  "location": {
                    "valid": false,
                    "lat": null,
                    "lon": null,
                    "zone_id": null
                  },
                  "incident": {
                    "asset_type": "BRAILLE_BLOCK",
                    "block_type": "UNKNOWN",
                    "defect_type": "DAMAGED",
                    "confidence": 0.87
                  },
                  "evidence": {
                    "image_id": null,
                    "captured_at": "2026-07-26T05:15:34.120Z",
                    "image_width": 1280,
                    "image_height": 720,
                    "bbox_norm": [0.16, 0.42, 0.81, 0.95]
                  },
                  "model": {
                    "name": "tbs-braille-yolo",
                    "version": "1.0.0"
                  }
                }
                """;

        ParsedMqttMessage parsed = parser.parse(message(
                "tbs/v2/robots/RBT-001/events/incident",
                1,
                payload
        ));

        assertThat(parsed.payload()).isInstanceOf(IncidentEventPayload.class);
        IncidentEventPayload incident = (IncidentEventPayload) parsed.payload();
        assertThat(incident.incident().attributes()).isNull();
        assertThat(incident.location().valid()).isFalse();
        assertThat(incident.evidence().imageId()).isNull();
    }

    @Test
    void rejectsRobotIdAndQosMismatchesWithBusinessErrorCodes() {
        assertErrorCode(
                () -> parser.parse(message(
                        "tbs/v2/robots/RBT-001/telemetry",
                        0,
                        validTelemetry("RBT-999")
                )),
                ErrorCode.MQTT_ROBOT_ID_MISMATCH
        );
        assertErrorCode(
                () -> parser.parse(message(
                        "tbs/v2/robots/RBT-001/telemetry",
                        1,
                        validTelemetry("RBT-001")
                )),
                ErrorCode.MQTT_QOS_MISMATCH
        );
    }

    @Test
    void rejectsInvalidGpsAndUnsupportedPayloadVersion() {
        assertErrorCode(
                () -> parser.parse(message(
                        "tbs/v2/robots/RBT-001/telemetry",
                        0,
                        telemetryPayload("RBT-001", "2.0", """
                                "valid": false,
                                "lat": 37.4979,
                                "lon": null,
                                "heading_deg": null
                                """)
                )),
                ErrorCode.MQTT_PAYLOAD_VALIDATION_FAILED
        );
        assertErrorCode(
                () -> parser.parse(message(
                        "tbs/v2/robots/RBT-001/telemetry",
                        0,
                        telemetryPayload("RBT-001", "1.0", """
                                "valid": true,
                                "lat": 37.4979,
                                "lon": 127.0276,
                                "heading_deg": null
                                """)
                )),
                ErrorCode.MQTT_SCHEMA_VERSION_UNSUPPORTED
        );
    }

    @Test
    void rejectsUnknownJsonMembers() {
        String payload = validTelemetry("RBT-001")
                .replace("\"mission\": {", "\"unexpected\": true, \"mission\": {");

        assertErrorCode(
                () -> parser.parse(message(
                        "tbs/v2/robots/RBT-001/telemetry",
                        0,
                        payload
                )),
                ErrorCode.MQTT_PAYLOAD_MALFORMED
        );
    }

    private static MqttMessageParser createParser() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        Validator validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
        return new MqttMessageParser(
                new MqttTopicParser(),
                objectMapper,
                validator
        );
    }

    private static MqttInboundMessage message(
            String topic,
            int qos,
            String payload
    ) {
        return new MqttInboundMessage(topic, payload, qos, RECEIVED_AT);
    }

    private static String validTelemetry(String robotId) {
        return telemetryPayload(robotId, "2.0", """
                "valid": true,
                "lat": 37.4979,
                "lon": 127.0276,
                "heading_deg": null
                """);
    }

    private static String telemetryPayload(
            String robotId,
            String schemaVersion,
            String position
    ) {
        return """
                {
                  "schema_version": "%s",
                  "message_id": "fb9c4472-226d-4473-b684-f0d8282292bd",
                  "robot_id": "%s",
                  "boot_id": "572b2e59-d31f-445f-a43d-7df80ba64203",
                  "seq": 4312,
                  "sampled_at": "2026-07-26T05:15:35Z",
                  "mission_id": "MIS-20260726-001",
                  "position": {
                    %s
                  },
                  "motion": {
                    "moving": true,
                    "speed_mps": null
                  },
                  "battery": {
                    "valid": true,
                    "percent": 67.4,
                    "state": "NORMAL",
                    "source": "MEASURED"
                  },
                  "system": {
                    "jetson_temp_c": 41.5
                  },
                  "mission": {
                    "state": "RUNNING",
                    "elapsed_sec": 583,
                    "incident_count": 2
                  }
                }
                """.formatted(schemaVersion, robotId, position);
    }

    private static void assertErrorCode(
            Runnable operation,
            ErrorCode expectedErrorCode
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(expectedErrorCode);
    }
}
