package com.gilbom.robot.mqtt.config;

import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;
import org.eclipse.paho.mqttv5.client.IMqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.ClientManager;
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
        options.setServerURIs(new String[]{properties.getBroker()});// 브로커 주소 연결
        options.setUserName(properties.getUsername());				// MQTT 서비스 사용자 이름
        options.setPassword(properties.getPassword().getBytes(StandardCharsets.UTF_8)); // PW로 증명
        options.setSocketFactory(
                securityStrategyFactory
                        .getNowStrategy()  // tls or mtls or ..평문
                        .createSslContext() // 인증 정보  브로커를 신뢰할 트러스트스토어(CA 인증서) +클라이언트 인증서(mutual TLS라면)
                        .getSocketFactory()//완성된 SSLContext에서 실제 소켓을 생성할 팩토리
        );
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay(Math.toIntExact(
                properties.getReconnectMaxDelay().toMillis()));
        // Keep the broker-side subscription session so QoS 1 messages published
        // during a short backend disconnect can be delivered after reconnect.
        options.setCleanStart(false);
        options.setSessionExpiryInterval(properties.getSessionExpiry().getSeconds());
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        return options;
    }
    //실제 MQTT 클라이언트 연결을 관리하는 매니저
    @Bean
    public BoundedMqttv5ClientManager mqttClientManager(
            MqttConnectionOptions connectionOptions,
            MqttProperties properties
    ) {
        BoundedMqttv5ClientManager clientManager =
                new BoundedMqttv5ClientManager(
                connectionOptions,
                properties.getClientId());//브로커에 접속하는 클라(스프링서버)
        clientManager.setCompletionTimeout(
                properties.getConnectCompletionTimeout().toMillis());
        clientManager.setDisconnectCompletionTimeout(
                properties.getDisconnectCompletionTimeout().toMillis());
        return clientManager;
    }
    //브로커로부터 받은 메시지가 흘러 들어오는 파이프/ path-variable로 관리하므로 구독자가 정확히 하나 처리
    @Bean
    public MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }
    //브로커로 메시지를 보낼 때 쓰는 파이프입니다.
    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    //브로커 토픽을 구독(subscribe)하는 역할
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
        adapter.setCompletionTimeout(
                properties.getConnectCompletionTimeout().toMillis());
        adapter.setDisconnectCompletionTimeout(
                properties.getDisconnectCompletionTimeout().toMillis());
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundHandler(
            ClientManager<IMqttAsyncClient, MqttConnectionOptions> clientManager,
            MqttProperties properties
    ) {
        Mqttv5PahoMessageHandler handler = new Mqttv5PahoMessageHandler(clientManager);
        // Deliberately do not configure a default topic. Every application
        // publish must supply MqttHeaders.TOPIC for the target robot; otherwise
        // it must fail instead of silently falling back to robot-test.
        handler.setDefaultQos(properties.getQos());
        handler.setAsync(true);
        return handler;
    }
}
