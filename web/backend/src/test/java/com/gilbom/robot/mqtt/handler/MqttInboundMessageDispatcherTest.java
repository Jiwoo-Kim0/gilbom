package com.gilbom.robot.mqtt.handler;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.client.MqttInboundMessage;
import com.gilbom.robot.mqtt.parser.MqttMessageParser;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicDirection;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.mqtt.topic.ParsedMqttTopic;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttInboundMessageDispatcherTest {

    @Test
    void dispatchesParsedMessageToItsRegisteredTopicHandler() {
        MqttMessageParser parser = mock(MqttMessageParser.class);
        List<MqttTopicMessageHandler<?>> handlers = inboundHandlers();
        MqttTopicMessageHandler<?> telemetryHandler = handlerFor(
                handlers,
                MqttTopicType.TELEMETRY
        );
        ParsedMqttMessage parsedMessage = parsedMessage(MqttTopicType.TELEMETRY);
        MqttInboundMessage inboundMessage = inboundMessage();
        when(parser.parse(inboundMessage)).thenReturn(parsedMessage);
        MqttInboundMessageDispatcher dispatcher =
                new MqttInboundMessageDispatcher(parser, handlers);

        dispatcher.dispatch(inboundMessage);

        verify(telemetryHandler).handle(parsedMessage);
        handlers.stream()
                .filter(handler -> handler != telemetryHandler)
                .forEach(handler -> verify(handler, never()).handle(any()));
    }

    @Test
    void registersEveryRobotToServerTopicExactlyOnce() {
        MqttInboundMessageDispatcher dispatcher =
                new MqttInboundMessageDispatcher(
                        mock(MqttMessageParser.class),
                        inboundHandlers()
                );

        assertThat(dispatcher.registeredTopicTypes())
                .containsExactlyInAnyOrder(
                        Arrays.stream(MqttTopicType.values())
                                .filter(type ->
                                        type.direction()
                                                == MqttTopicDirection.ROBOT_TO_SERVER)
                                .toArray(MqttTopicType[]::new)
                );
    }

    @Test
    void rejectsMissingDuplicateAndOutboundHandlerRegistrations() {
        List<MqttTopicMessageHandler<?>> missing = inboundHandlers();
        missing.removeIf(handler ->
                handler.topicType() == MqttTopicType.TELEMETRY);

        assertThatThrownBy(() -> new MqttInboundMessageDispatcher(
                mock(MqttMessageParser.class),
                missing
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TELEMETRY");

        List<MqttTopicMessageHandler<?>> duplicate = inboundHandlers();
        duplicate.add(mockHandler(MqttTopicType.TELEMETRY));
        assertThatThrownBy(() -> new MqttInboundMessageDispatcher(
                mock(MqttMessageParser.class),
                duplicate
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("TELEMETRY");

        List<MqttTopicMessageHandler<?>> outbound = inboundHandlers();
        outbound.add(mockHandler(MqttTopicType.COMMAND_REQUEST));
        assertThatThrownBy(() -> new MqttInboundMessageDispatcher(
                mock(MqttMessageParser.class),
                outbound
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbound")
                .hasMessageContaining("COMMAND_REQUEST");
    }

    @Test
    void rejectsInvalidExternalMessageWithoutCallingAHandler() {
        MqttMessageParser parser = mock(MqttMessageParser.class);
        List<MqttTopicMessageHandler<?>> handlers = inboundHandlers();
        MqttInboundMessage inboundMessage = inboundMessage();
        when(parser.parse(inboundMessage)).thenThrow(
                new BusinessException(ErrorCode.MQTT_PAYLOAD_MALFORMED)
        );
        MqttInboundMessageDispatcher dispatcher =
                new MqttInboundMessageDispatcher(parser, handlers);

        assertThatNoException().isThrownBy(() ->
                dispatcher.dispatch(inboundMessage));

        handlers.forEach(handler ->
                verify(handler, never()).handle(any()));
    }

    private static List<MqttTopicMessageHandler<?>> inboundHandlers() {
        List<MqttTopicMessageHandler<?>> handlers = new ArrayList<>();
        Arrays.stream(MqttTopicType.values())
                .filter(type ->
                        type.direction() == MqttTopicDirection.ROBOT_TO_SERVER)
                .map(MqttInboundMessageDispatcherTest::mockHandler)
                .forEach(handlers::add);
        return handlers;
    }

    @SuppressWarnings("unchecked")
    private static MqttTopicMessageHandler<?> mockHandler(
            MqttTopicType topicType
    ) {
        MqttTopicMessageHandler<?> handler =
                mock(MqttTopicMessageHandler.class);
        when(handler.topicType()).thenReturn(topicType);
        return handler;
    }

    private static MqttTopicMessageHandler<?> handlerFor(
            List<MqttTopicMessageHandler<?>> handlers,
            MqttTopicType topicType
    ) {
        return handlers.stream()
                .filter(handler -> handler.topicType() == topicType)
                .findFirst()
                .orElseThrow();
    }

    private static ParsedMqttMessage parsedMessage(MqttTopicType topicType) {
        ParsedMqttMessage parsedMessage = mock(ParsedMqttMessage.class);
        when(parsedMessage.topic()).thenReturn(
                new ParsedMqttTopic(2, "robot-test", topicType)
        );
        return parsedMessage;
    }

    private static MqttInboundMessage inboundMessage() {
        return new MqttInboundMessage(
                "tbs/v2/robots/robot-test/telemetry",
                "{}",
                0,
                Instant.parse("2026-07-26T16:35:00Z")
        );
    }
}
