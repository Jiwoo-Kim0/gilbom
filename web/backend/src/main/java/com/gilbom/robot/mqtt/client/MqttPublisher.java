package com.gilbom.robot.mqtt.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.gilbom.robot.mqtt.config.MqttProperties;

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

    public boolean publish(String topic, String payload) {
        return publish(topic, payload, properties.getQos(), false);
    }

    public boolean publish(
            String topic,
            String payload,
            int qos,
            boolean retained
    ) {
        return publish(topic, payload, qos, retained, null);
    }

    public boolean publish(
            String topic,
            String payload,
            int qos,
            boolean retained,
            Long messageExpiryIntervalSeconds
    ) {
        Assert.hasText(topic, "MQTT topic must not be blank");
        Assert.notNull(payload, "MQTT payload must not be null");
        Assert.isTrue(
                qos >= 0 && qos <= 2,
                "MQTT qos must be between 0 and 2");
        Assert.isTrue(
                messageExpiryIntervalSeconds == null
                        || messageExpiryIntervalSeconds > 0,
                "MQTT message expiry must be positive");

        MessageBuilder<String> builder = MessageBuilder
                .withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, qos)
                .setHeader(MqttHeaders.RETAINED, retained);
        if (messageExpiryIntervalSeconds != null) {
            builder.setHeader(
                    MqttHeaders.MESSAGE_EXPIRY_INTERVAL,
                    messageExpiryIntervalSeconds);
        }
        return outboundChannel.send(builder.build());
    }
}
