package com.gilbom.robot.mqtt.topic;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttTopicParserTest {

    private final MqttTopicParser parser = new MqttTopicParser();

    @Test
    void parsesAndFormatsV2Topic() {
        ParsedMqttTopic parsed = parser.parse(
                "tbs/v2/robots/RBT-001/events/safety"
        );

        assertThat(parsed.version()).isEqualTo(2);
        assertThat(parsed.mqttId()).isEqualTo("RBT-001");
        assertThat(parsed.type()).isEqualTo(MqttTopicType.SAFETY_EVENT);
        assertThat(parsed.type().qos()).isEqualTo(1);
        assertThat(parsed.type().retained()).isFalse();
        assertThat(parser.format(MqttTopicType.COMMAND_REQUEST, "RBT-001"))
                .isEqualTo("tbs/v2/robots/RBT-001/commands/request");
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertErrorCode(
                () -> parser.parse("tbs/v1/robots/RBT-001/telemetry"),
                ErrorCode.MQTT_SCHEMA_VERSION_UNSUPPORTED
        );
    }

    @Test
    void rejectsUnknownOrWildcardTopic() {
        assertErrorCode(
                () -> parser.parse("tbs/v2/robots/+/telemetry"),
                ErrorCode.MQTT_TOPIC_INVALID
        );
        assertErrorCode(
                () -> parser.parse("tbs/v2/robots/RBT-001/unknown"),
                ErrorCode.MQTT_TOPIC_UNSUPPORTED
        );
    }

    private void assertErrorCode(Runnable operation, ErrorCode errorCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(errorCode);
    }
}
