package com.gilbom.robot.mqtt.config;

import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;
import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.ClientManager;
import org.springframework.integration.mqtt.core.Mqttv5ClientManager;
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.Mqttv5PahoMessageHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.nio.charset.StandardCharsets;

@Configuration
public class MqttConnectionConfig {

    @Bean
    public MqttConnectionOptions mqttConnectionOptions(
            MqttProperties properties,
            MqttSecurityStrategyFactory securityStrategyFactory
    ) {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{properties.getBroker()});
        options.setUserName(properties.getUsername());
        options.setPassword(properties.getPassword().getBytes(StandardCharsets.UTF_8));
        options.setSocketFactory(
                securityStrategyFactory
                        .getNowStrategy()
                        .createSslContext()
                        .getSocketFactory()
        );
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        return options;
    }

    @Bean
    public ClientManager<IMqttAsyncClient, MqttConnectionOptions> mqttClientManager(
            MqttConnectionOptions connectionOptions,
            MqttProperties properties
    ) {
        return new Mqttv5ClientManager(connectionOptions, properties.getClientId());
    }

    @Bean
    public MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public Mqttv5PahoMessageDrivenChannelAdapter mqttInboundAdapter(
            ClientManager<IMqttAsyncClient, MqttConnectionOptions> clientManager,
            MqttProperties properties,
            @Qualifier("mqttInboundChannel") MessageChannel mqttInboundChannel
    ) {
        Mqttv5PahoMessageDrivenChannelAdapter adapter =
                new Mqttv5PahoMessageDrivenChannelAdapter(
                        clientManager,
                        properties.getSubscribeTopics()
                );
        adapter.setQos(properties.getQos());
        adapter.setPayloadType(String.class);
        adapter.setOutputChannel(mqttInboundChannel);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundHandler(
            ClientManager<IMqttAsyncClient, MqttConnectionOptions> clientManager,
            MqttProperties properties
    ) {
        Mqttv5PahoMessageHandler handler = new Mqttv5PahoMessageHandler(clientManager);
        handler.setDefaultTopic(properties.getPublishTopic());
        handler.setDefaultQos(properties.getQos());
        handler.setAsync(true);
        return handler;
    }
}
