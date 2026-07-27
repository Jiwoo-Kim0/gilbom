package com.gilbom.robot.mqtt.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class MqttSubscriber {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriber.class);

    //Spring이 제공하는 이벤트 발행기
    private final ApplicationEventPublisher eventPublisher;

    public MqttSubscriber(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @ServiceActivator(inputChannel = "mqttInboundChannel")
    public void receive(Message<?> message) {
        String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class);
        Integer qos = message.getHeaders().get(MqttHeaders.RECEIVED_QOS, Integer.class);
        String payload = payloadAsString(message.getPayload());

        MqttInboundMessage inboundMessage = new MqttInboundMessage(
                topic,
                payload,
                qos == null ? 0 : qos,
                Instant.now()
        );
        log.debug(
                "MQTT message received. topic={}, qos={}, payloadLength={}",
                topic,
                inboundMessage.qos(),
                payload.length()
        );
        eventPublisher.publishEvent(inboundMessage);
    }

    private String payloadAsString(Object payload) {
        if (payload instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(payload);
    }
}
