package com.gilbom.robot.mqtt.handler;

import com.gilbom.common.error.BusinessException;
import com.gilbom.robot.mqtt.client.MqttInboundMessage;
import com.gilbom.robot.mqtt.parser.MqttMessageParser;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicDirection;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MqttInboundMessageDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(MqttInboundMessageDispatcher.class);

    private final MqttMessageParser parser;
    private final Map<MqttTopicType, MqttTopicMessageHandler<?>> handlers;

    public MqttInboundMessageDispatcher(
            MqttMessageParser parser,
            List<MqttTopicMessageHandler<?>> handlers
    ) {
        this.parser = parser;
        this.handlers = registerHandlers(handlers);
    }

    @EventListener
    public void dispatch(MqttInboundMessage inboundMessage) {
        try {
            ParsedMqttMessage parsedMessage = parser.parse(inboundMessage);
            MqttTopicMessageHandler<?> handler = handlers.get(
                    parsedMessage.topic().type()
            );
            if (handler == null) {
                throw new IllegalStateException(
                        "No MQTT handler registered for "
                                + parsedMessage.topic().type()
                );
            }
            handler.handle(parsedMessage);
        } catch (BusinessException exception) {
            log.warn(
                    "MQTT inbound message rejected. topic={}, qos={}, errorCode={}",
                    inboundMessage.topic(),
                    inboundMessage.qos(),
                    exception.errorCode()
            );
        }
    }

    Set<MqttTopicType> registeredTopicTypes() {
        return handlers.keySet();
    }

    private Map<MqttTopicType, MqttTopicMessageHandler<?>> registerHandlers(
            List<MqttTopicMessageHandler<?>> candidates
    ) {
        Objects.requireNonNull(candidates, "handlers must not be null");

        EnumMap<MqttTopicType, MqttTopicMessageHandler<?>> registered =
                new EnumMap<>(MqttTopicType.class);
        for (MqttTopicMessageHandler<?> handler : candidates) {
            Objects.requireNonNull(handler, "handler must not be null");
            MqttTopicType topicType = handler.topicType();
            if (topicType.direction() != MqttTopicDirection.ROBOT_TO_SERVER) {
                throw new IllegalStateException(
                        "Inbound handler cannot register outbound topic " + topicType
                );
            }
            MqttTopicMessageHandler<?> duplicate =
                    registered.putIfAbsent(topicType, handler);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "Duplicate MQTT handler registration for " + topicType
                );
            }
        }

        Set<MqttTopicType> required = Arrays.stream(MqttTopicType.values())
                .filter(type ->
                        type.direction() == MqttTopicDirection.ROBOT_TO_SERVER)
                .collect(Collectors.toCollection(
                        () -> EnumSet.noneOf(MqttTopicType.class)
                ));
        if (!registered.keySet().equals(required)) {
            EnumSet<MqttTopicType> missing = EnumSet.copyOf(required);
            missing.removeAll(registered.keySet());
            throw new IllegalStateException(
                    "Missing MQTT inbound handlers: " + missing
            );
        }

        return Collections.unmodifiableMap(registered);
    }
}
