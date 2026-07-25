package com.gilbom.robot.mqtt.client;

import com.gilbom.robot.mqtt.config.MqttProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class MqttPublisher {

    private final MessageChannel outboundChannel;
    private final MqttProperties properties;

    public MqttPublisher(
            @Qualifier("mqttOutboundChannel") MessageChannel outboundChannel,
            MqttProperties properties
    ) {
        this.outboundChannel = outboundChannel;
        this.properties = properties;
    }

    public boolean publish(String payload) {
        return publish(properties.getPublishTopic(), payload);
    }

    public boolean publish(String topic, String payload) {
        Assert.hasText(topic, "MQTT topic must not be blank");
        Assert.notNull(payload, "MQTT payload must not be null");

        Message<String> message = MessageBuilder
                .withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, properties.getQos())
                .build();
        return outboundChannel.send(message);
    }
}
