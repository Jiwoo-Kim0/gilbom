package com.gilbom.robot.mqtt.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter;

import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;
import com.gilbom.robot.mqtt.strategy.MqttSecurityStrategy;

class MqttConnectionConfigTest {

    @Test
    void limitsAutomaticReconnectBackoff() throws Exception {
        MqttProperties properties = new MqttProperties();
        properties.setNowMqttSecurityStrategy("tls");
        properties.setBroker("ssl://localhost:8883");
        properties.setClientId("mqtt-config-test");
        properties.setUsername("backend");
        properties.setPassword("secret");
        properties.setReconnectMaxDelay(Duration.ofSeconds(5));

        MqttSecurityStrategy strategy = new MqttSecurityStrategy() {
            @Override
            public String mode() {
                return "tls";
            }

            @Override
            public SSLContext createSslContext() {
                try {
                    return SSLContext.getDefault();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        MqttSecurityStrategyFactory factory =
                new MqttSecurityStrategyFactory(
                        properties,
                        List.of(strategy));

        MqttConnectionOptions options = new MqttConnectionConfig()
                .mqttConnectionOptions(properties, factory);

        assertThat(options.isAutomaticReconnect()).isTrue();
        assertThat(options.getMaxReconnectDelay()).isEqualTo(5_000);
    }

    @Test
    void boundsClientManagerConnectAndDisconnectWaits() {
        MqttProperties properties = new MqttProperties();
        properties.setBroker("ssl://localhost:8883");
        properties.setClientId("mqtt-lifecycle-test");
        properties.setConnectCompletionTimeout(Duration.ofSeconds(7));
        properties.setDisconnectCompletionTimeout(Duration.ofSeconds(3));
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{"ssl://localhost:8883"});

        BoundedMqttv5ClientManager clientManager =
                new MqttConnectionConfig()
                .mqttClientManager(
                        options,
                        properties);
        DirectFieldAccessor fields =
                new DirectFieldAccessor(clientManager);

        assertThat(fields.getPropertyValue("completionTimeout"))
                .isEqualTo(7_000L);
        assertThat(fields.getPropertyValue("disconnectCompletionTimeout"))
                .isEqualTo(3_000L);

        properties.setSubscribeTopic("runtime/check/#");
        Mqttv5PahoMessageDrivenChannelAdapter adapter =
                new MqttConnectionConfig().mqttInboundAdapter(
                        clientManager,
                        properties,
                        new DirectChannel());
        DirectFieldAccessor adapterFields =
                new DirectFieldAccessor(adapter);

        assertThat(adapterFields.getPropertyValue("completionTimeout"))
                .isEqualTo(7_000L);
        assertThat(adapterFields.getPropertyValue(
                "disconnectCompletionTimeout"))
                .isEqualTo(3_000L);
    }
}
