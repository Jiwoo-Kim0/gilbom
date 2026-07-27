package com.gilbom.robot.mqtt.client;

import com.gilbom.robot.mqtt.config.MqttProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttPublisherTest {

    @Test
    void publishesPayloadWithExplicitTopicPolicy() {
        MessageChannel channel = mock(MessageChannel.class);
        when(channel.send(any(Message.class))).thenReturn(true);

        MqttProperties properties = new MqttProperties();
        MqttPublisher publisher = new MqttPublisher(channel, properties);

        boolean accepted = publisher.publish(
                "tbs/v2/robots/RBT-001/commands/request",
                "{\"schema_version\":\"2.0\"}",
                1,
                false
        );

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(channel).send(captor.capture());
        Message<?> message = captor.getValue();

        assertThat(accepted).isTrue();
        assertThat(message.getHeaders().get(MqttHeaders.TOPIC))
                .isEqualTo("tbs/v2/robots/RBT-001/commands/request");
        assertThat(message.getHeaders().get(MqttHeaders.QOS)).isEqualTo(1);
        assertThat(message.getHeaders().get(MqttHeaders.RETAINED)).isEqualTo(false);
    }
}
