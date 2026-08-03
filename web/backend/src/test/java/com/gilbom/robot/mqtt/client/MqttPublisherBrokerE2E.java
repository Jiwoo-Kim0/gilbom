package com.gilbom.robot.mqtt.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.outbound.Mqttv5PahoMessageHandler;
import org.springframework.messaging.converter.GenericMessageConverter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.strategy.TlsMqttSecurityStrategy;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;

class MqttPublisherBrokerE2E {

    private static final String BROKER_ENV = "MQTT_E2E_BROKER";
    private static final String CA_ENV = "MQTT_E2E_CA_CERTIFICATE";
    private static final String SERVER_USERNAME_ENV =
            "MQTT_E2E_SERVER_USERNAME";
    private static final String SERVER_PASSWORD_ENV =
            "MQTT_E2E_SERVER_PASSWORD";
    private static final String ROBOT_USERNAME_ENV =
            "MQTT_E2E_ROBOT_USERNAME";
    private static final String ROBOT_PASSWORD_ENV =
            "MQTT_E2E_ROBOT_PASSWORD";

    @Test
    @Tag("mqtt-broker-e2e")
    void expiresOfflineCommandInsideRealTlsBroker() throws Exception {
        String broker = requiredEnvironment(BROKER_ENV);
        String caCertificate = requiredEnvironment(CA_ENV);
        String serverUsername = requiredEnvironment(SERVER_USERNAME_ENV);
        String serverPassword = requiredEnvironment(SERVER_PASSWORD_ENV);
        String mqttId = requiredEnvironment(ROBOT_USERNAME_ENV);
        String robotPassword = requiredEnvironment(ROBOT_PASSWORD_ENV);
        String topic = "tbs/v2/robots/" + mqttId + "/commands/request";
        String testId = UUID.randomUUID().toString();

        List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivery = new CountDownLatch(1);
        MqttAsyncClient robotClient = new MqttAsyncClient(
                broker,
                "ttl-e2e-robot-" + testId,
                new MemoryPersistence()
        );
        robotClient.setCallback(new CapturingCallback(received, delivery));
        Mqttv5PahoMessageHandler outboundHandler = null;
        GenericApplicationContext publisherContext = null;

        try {
            MqttConnectionOptions firstConnection = connectionOptions(
                    broker,
                    caCertificate,
                    mqttId,
                    robotPassword,
                    true
            );
            IMqttToken initialConnect = robotClient.connect(firstConnection);
            initialConnect.waitForCompletion(5_000);
            robotClient.subscribe(topic, 1).waitForCompletion(5_000);
            robotClient.disconnect().waitForCompletion(5_000);

            MqttProperties publisherProperties = mqttProperties(
                    broker,
                    caCertificate,
                    serverUsername,
                    serverPassword
            );
            MqttConnectionOptions publisherConnection = connectionOptions(
                    broker,
                    caCertificate,
                    serverUsername,
                    serverPassword,
                    true
            );
            publisherContext = new GenericApplicationContext();
            publisherContext.registerBean(
                    "integrationArgumentResolverMessageConverter",
                    GenericMessageConverter.class
            );
            publisherContext.registerBean(
                    "mqttOutboundHandler",
                    Mqttv5PahoMessageHandler.class,
                    () -> {
                        Mqttv5PahoMessageHandler handler =
                                new Mqttv5PahoMessageHandler(
                                        publisherConnection,
                                        "ttl-e2e-server-" + testId
                                );
                        handler.setAsync(false);
                        return handler;
                    }
            );
            publisherContext.refresh();
            outboundHandler = publisherContext.getBean(
                    "mqttOutboundHandler",
                    Mqttv5PahoMessageHandler.class
            );
            outboundHandler.start();

            DirectChannel channel = new DirectChannel();
            channel.subscribe(outboundHandler);
            MqttV2Publisher publisher = new MqttV2Publisher(
                    new MqttPublisher(channel, publisherProperties),
                    new MqttTopicParser(),
                    new ObjectMapper().registerModule(new JavaTimeModule())
            );

            Instant issuedAt = Instant.now();
            UUID shortCommandId = UUID.randomUUID();
            UUID longCommandId = UUID.randomUUID();
            MqttV2Publisher.PublishResult shortResult =
                    publisher.publishCommand(
                            mqttId,
                            command(shortCommandId, issuedAt, 2)
                    );
            MqttV2Publisher.PublishResult longResult =
                    publisher.publishCommand(
                            mqttId,
                            command(longCommandId, issuedAt, 30)
                    );

            assertThat(shortResult.retained()).isFalse();
            assertThat(longResult.retained()).isFalse();
            assertThat(shortResult.qos()).isEqualTo(1);
            assertThat(longResult.qos()).isEqualTo(1);

            Thread.sleep(4_000);

            MqttConnectionOptions resumedConnection = connectionOptions(
                    broker,
                    caCertificate,
                    mqttId,
                    robotPassword,
                    false
            );
            IMqttToken resumed = robotClient.connect(resumedConnection);
            resumed.waitForCompletion(5_000);

            assertThat(resumed.getSessionPresent()).isTrue();
            assertThat(delivery.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);

            assertThat(received)
                    .noneMatch(message -> message.payload().contains(
                            shortCommandId.toString()
                    ));
            assertThat(received)
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message.payload())
                                .contains(longCommandId.toString());
                        assertThat(message.remainingExpirySeconds())
                                .isBetween(24L, 27L);
                        assertThat(message.retained()).isFalse();
                    });
        } finally {
            if (robotClient.isConnected()) {
                robotClient.disconnect().waitForCompletion(5_000);
            }
            robotClient.close();
            if (outboundHandler != null) {
                outboundHandler.stop();
            }
            if (publisherContext != null) {
                publisherContext.close();
            }
        }
    }

    private static CommandRequestPayload command(
            UUID commandId,
            Instant issuedAt,
            long ttlSeconds
    ) {
        return new CommandRequestPayload(
                "2.0",
                commandId,
                CommandRequestPayload.CommandType.EMERGENCY_STOP,
                issuedAt,
                issuedAt.plusSeconds(ttlSeconds),
                new CommandRequestPayload.RequestedBy(
                        CommandRequestPayload.ActorType.ADMIN,
                        "mqtt-ttl-e2e"
                ),
                new CommandRequestPayload.Parameters(
                        null,
                        null,
                        "mqtt ttl broker e2e"
                )
        );
    }

    private static MqttProperties mqttProperties(
            String broker,
            String caCertificate,
            String username,
            String password
    ) {
        MqttProperties properties = new MqttProperties();
        properties.setNowMqttSecurityStrategy("tls");
        properties.setBroker(broker);
        properties.setClientId("ttl-e2e-properties");
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setSubscribeTopic("tbs/v2/robots/+/telemetry");
        properties.setPublishTopic("tbs/v2/robots/+/commands/request");
        properties.setQos(1);
        properties.getSecurity().setTrustCertificate(caCertificate);
        return properties;
    }

    private static MqttConnectionOptions connectionOptions(
            String broker,
            String caCertificate,
            String username,
            String password,
            boolean cleanStart
    ) {
        MqttProperties properties = mqttProperties(
                broker,
                caCertificate,
                username,
                password
        );
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{broker});
        options.setUserName(username);
        options.setPassword(password.getBytes(StandardCharsets.UTF_8));
        options.setSocketFactory(
                new TlsMqttSecurityStrategy(properties)
                        .createSslContext()
                        .getSocketFactory()
        );
        options.setCleanStart(cleanStart);
        options.setSessionExpiryInterval(60L);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        return options;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be set for the MQTT Broker E2E test"
            );
        }
        return value;
    }

    private record ReceivedMessage(
            String payload,
            Long remainingExpirySeconds,
            boolean retained
    ) {
    }

    private static final class CapturingCallback implements MqttCallback {

        private final List<ReceivedMessage> received;
        private final CountDownLatch delivery;

        private CapturingCallback(
                List<ReceivedMessage> received,
                CountDownLatch delivery
        ) {
            this.received = received;
            this.delivery = delivery;
        }

        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            received.add(new ReceivedMessage(
                    new String(
                            message.getPayload(),
                            StandardCharsets.UTF_8
                    ),
                    message.getProperties().getMessageExpiryInterval(),
                    message.isRetained()
            ));
            delivery.countDown();
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
        }

        @Override
        public void connectComplete(
                boolean reconnect,
                String serverUri
        ) {
        }

        @Override
        public void authPacketArrived(
                int reasonCode,
                org.eclipse.paho.mqttv5.common.packet.MqttProperties properties
        ) {
        }
    }
}
