package com.gilbom.robot.mqtt.client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqttSubscriberTest {

    @Test
    void convertsReceivedMessageToMqttEvent() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        MqttSubscriber subscriber = new MqttSubscriber(eventPublisher);

        subscriber.receive(
                MessageBuilder
                        .withPayload("{\"battery\":87}")
                        .setHeader(MqttHeaders.RECEIVED_TOPIC, "robots/robot-001/telemetry")
                        .setHeader(MqttHeaders.RECEIVED_QOS, 1)
                        .build()
        );

        ArgumentCaptor<MqttInboundMessage> captor =
                ArgumentCaptor.forClass(MqttInboundMessage.class);
        verify(eventPublisher).publishEvent(captor.capture());

        MqttInboundMessage event = captor.getValue();
        assertThat(event.topic()).isEqualTo("robots/robot-001/telemetry");
        assertThat(event.payload()).isEqualTo("{\"battery\":87}");
        assertThat(event.qos()).isEqualTo(1);
        assertThat(event.receivedAt()).isNotNull();
    }
}
