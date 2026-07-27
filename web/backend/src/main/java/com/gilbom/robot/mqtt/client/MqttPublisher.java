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
            @Qualifier("mqttOutboundChannel") MessageChannel outboundChannel,//config의 outboundChannel - bean 매서드임
            MqttProperties properties
    ) {
        this.outboundChannel = outboundChannel;
        this.properties = properties;
    }

    /*Gara Test*/
//    public boolean publish(String payload) {
//        return publish(properties.getPublishTopic(), payload);
//    }

    /*Topic+Payroad*/
    public boolean publish(String topic, String payload) {
        return publish(topic, payload, properties.getQos(), false);
    }
    /*+Other Options*/
    public boolean publish(String topic, String payload, int qos, boolean retained) {
        Assert.hasText(topic, "MQTT topic must not be blank");
        Assert.notNull(payload, "MQTT payload must not be null");
        Assert.isTrue(qos >= 0 && qos <= 2, "MQTT qos must be between 0 and 2");

        Message<String> message = MessageBuilder//쏜다.
                .withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, qos)
                .setHeader(MqttHeaders.RETAINED, retained)
                .build();
        return outboundChannel.send(message);
    }
}
