package com.gilbom.robot.mqtt.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.robot.data.mqtt.v2.common.EventSource;
import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mission.MissionEventIngestionService;
import com.gilbom.robot.mqtt.config.MqttInboundFreshnessProperties;
import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.handler.IncidentEventMqttMessageHandler;
import com.gilbom.robot.mqtt.handler.MissionEventMqttMessageHandler;
import com.gilbom.robot.mqtt.handler.MqttInboundMessageDispatcher;
import com.gilbom.robot.mqtt.handler.MqttTopicMessageHandler;
import com.gilbom.robot.mqtt.parser.MqttInboundFreshnessGuard;
import com.gilbom.robot.mqtt.parser.MqttMessageParser;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.replay.MqttReplayProtectionService;
import com.gilbom.robot.mqtt.strategy.TlsMqttSecurityStrategy;
import com.gilbom.robot.mqtt.topic.MqttTopicParser;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

import io.micrometer.core.instrument.MeterRegistry;

@Testcontainers
@SpringBootTest(
        classes = MqttInboundBrokerE2E.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.main.web-application-type=none",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class MqttInboundBrokerE2E {

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
    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID ADMIN_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000001");
    private static final UUID ROBOT_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000002");
    private static final UUID AREA_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000003");
    private static final UUID ROUTE_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000004");
    private static final UUID MISSION_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000005");
    private static final String MISSION_CODE = "MIS-20260730-991";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_mqtt_inbound_broker_e2e")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.default-schema", () -> "flyway_history");
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MqttInboundMessageDispatcher dispatcher;

    private final ObjectMapper wireMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @BeforeEach
    void createFixture() {
        createAdmin();
        createRobot(requiredEnvironment(ROBOT_USERNAME_ENV));
        createPatrolAndMission();
        jdbcTemplate.update("""
                INSERT INTO telemetry.robot_current_state (
                    robot_id,
                    connectivity_status
                ) VALUES (?, 'OFFLINE')
                """,
                ROBOT_ID);
    }

    @Test
    @Tag("mqtt-broker-e2e")
    void rejectsStaleEventsAndDeduplicatesRetransmissionThroughTlsBroker()
            throws Exception {
        String broker = requiredEnvironment(BROKER_ENV);
        String caCertificate = requiredEnvironment(CA_ENV);
        String serverUsername = requiredEnvironment(SERVER_USERNAME_ENV);
        String serverPassword = requiredEnvironment(SERVER_PASSWORD_ENV);
        String mqttId = requiredEnvironment(ROBOT_USERNAME_ENV);
        String robotPassword = requiredEnvironment(ROBOT_PASSWORD_ENV);
        String topicPrefix = "tbs/v2/robots/" + mqttId + "/events/";
        String testId = UUID.randomUUID().toString();
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        MqttAsyncClient backendClient = new MqttAsyncClient(
                broker,
                "inbound-e2e-server-" + testId);
        backendClient.setCallback(new DispatchingCallback(
                dispatcher,
                deliveries,
                callbackFailure));
        MqttAsyncClient robotClient = new MqttAsyncClient(
                broker,
                "inbound-e2e-robot-" + testId);

        try {
            backendClient.connect(connectionOptions(
                    broker,
                    caCertificate,
                    serverUsername,
                    serverPassword)).waitForCompletion(5_000);
            backendClient.subscribe(
                    topicPrefix + "#",
                    1).waitForCompletion(5_000);
            robotClient.connect(connectionOptions(
                    broker,
                    caCertificate,
                    mqttId,
                    robotPassword)).waitForCompletion(5_000);

            double expiredBefore = rejectionCount(
                    MqttTopicType.MISSION_EVENT,
                    "expired");
            double futureBefore = rejectionCount(
                    MqttTopicType.INCIDENT_EVENT,
                    "future_timestamp");
            double conflictBefore = rejectionCount(
                    MqttTopicType.INCIDENT_EVENT,
                    "sequence_conflict");
            Instant now = Instant.now();

            publish(
                    robotClient,
                    topicPrefix + "mission",
                    missionEvent(
                            mqttId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            now.minusSeconds(61),
                            60));
            publish(
                    robotClient,
                    topicPrefix + "incident",
                    incidentEvent(
                            mqttId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            now.plusSeconds(30),
                            300));

            await(() -> deliveries.get() >= 2, callbackFailure);
            assertThat(rejectionCount(
                    MqttTopicType.MISSION_EVENT,
                    "expired")).isEqualTo(expiredBefore + 1.0);
            assertThat(rejectionCount(
                    MqttTopicType.INCIDENT_EVENT,
                    "future_timestamp")).isEqualTo(futureBefore + 1.0);
            assertNoEventSideEffects();

            UUID validBootId = UUID.randomUUID();
            MissionEventPayload valid = missionEvent(
                    mqttId,
                    UUID.randomUUID(),
                    validBootId,
                    1,
                    Instant.now(),
                    300);
            publish(robotClient, topicPrefix + "mission", valid);
            publish(robotClient, topicPrefix + "mission", valid);

            await(() -> deliveries.get() >= 4, callbackFailure);
            await(() -> count("telemetry.robot_event") == 1, callbackFailure);

            publish(
                    robotClient,
                    topicPrefix + "incident",
                    incidentEvent(
                            mqttId,
                            UUID.randomUUID(),
                            validBootId,
                            1,
                            Instant.now(),
                            300));
            await(() -> deliveries.get() >= 5, callbackFailure);

            assertThat(rejectionCount(
                    MqttTopicType.INCIDENT_EVENT,
                    "sequence_conflict")).isEqualTo(conflictBefore + 1.0);
            assertThat(count("telemetry.robot_event")).isOne();
            assertThat(count("robot.mission_status_history")).isOne();
            assertThat(count("defect.defect")).isZero();
            assertThat(count("defect.defect_observation")).isZero();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT status
                    FROM robot.mission
                    WHERE mission_id = ?
                    """,
                    String.class,
                    MISSION_ID)).isEqualTo("RUNNING");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT current_boot_id
                    FROM telemetry.robot_current_state
                    WHERE robot_id = ?
                    """,
                    UUID.class,
                    ROBOT_ID)).isEqualTo(validBootId);
            assertThat(callbackFailure.get()).isNull();
        } finally {
            disconnect(robotClient);
            disconnect(backendClient);
        }
    }

    private MissionEventPayload missionEvent(
            String mqttId,
            UUID eventId,
            UUID bootId,
            long sequence,
            Instant occurredAt,
            int ttlSeconds
    ) {
        return new MissionEventPayload(
                "2.0",
                eventId,
                MissionEventPayload.EventType.MISSION_STARTED,
                mqttId,
                bootId,
                sequence,
                EventSource.MISSION,
                occurredAt,
                Instant.now(),
                ttlSeconds,
                MISSION_CODE,
                null);
    }

    private IncidentEventPayload incidentEvent(
            String mqttId,
            UUID eventId,
            UUID bootId,
            long sequence,
            Instant occurredAt,
            int ttlSeconds
    ) {
        return new IncidentEventPayload(
                "2.0",
                eventId,
                IncidentEventPayload.EventType
                        .BRAILLE_BLOCK_INCIDENT_DETECTED,
                mqttId,
                bootId,
                sequence,
                EventSource.AI,
                occurredAt,
                Instant.now(),
                ttlSeconds,
                MISSION_CODE,
                new IncidentEventPayload.Location(
                        true,
                        37.4979,
                        127.0276,
                        "Z-99"),
                new IncidentEventPayload.Incident(
                        IncidentEventPayload.AssetType.BRAILLE_BLOCK,
                        IncidentEventPayload.BlockType.LINEAR,
                        IncidentEventPayload.DefectType.DAMAGED,
                        0.91,
                        new IncidentEventPayload.Attributes(0.32)),
                new IncidentEventPayload.Evidence(
                        null,
                        occurredAt,
                        1280,
                        720,
                        List.of(0.16, 0.42, 0.81, 0.95)),
                new IncidentEventPayload.Model(
                        "mqtt-inbound-e2e",
                        "1.0.0"));
    }

    private void publish(
            MqttAsyncClient client,
            String topic,
            MqttV2Payload payload
    ) throws Exception {
        MqttMessage message = new MqttMessage(
                wireMapper.writeValueAsBytes(payload));
        message.setQos(1);
        message.setRetained(false);
        client.publish(topic, message).waitForCompletion(5_000);
    }

    private void assertNoEventSideEffects() {
        assertThat(count("telemetry.robot_event")).isZero();
        assertThat(count("robot.mission_status_history")).isZero();
        assertThat(count("defect.defect")).isZero();
        assertThat(count("defect.defect_observation")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status
                FROM robot.mission
                WHERE mission_id = ?
                """,
                String.class,
                MISSION_ID)).isEqualTo("CREATED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_boot_id
                FROM telemetry.robot_current_state
                WHERE robot_id = ?
                """,
                UUID.class,
                ROBOT_ID)).isNull();
    }

    private double rejectionCount(
            MqttTopicType topicType,
            String reason
    ) {
        return meterRegistry.counter(
                "gilbom.mqtt.inbound.rejected",
                "topic",
                topicType.name(),
                "reason",
                reason).count();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private void createAdmin() {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    legacy_username,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    account_origin
                ) VALUES (
                    ?,
                    'ADM-MQTT-INBOUND-E2E',
                    'mqtt_inbound_e2e',
                    '{argon2}$argon2id$mqtt-inbound-e2e',
                    'MQTT Inbound E2E Admin',
                    'mqtt-inbound-e2e@example.invalid',
                    'ACTIVE',
                    TRUE,
                    'ADMIN_CREATED'
                )
                """,
                ADMIN_ID);
    }

    private void createRobot(String mqttId) {
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (
                    ?,
                    ?,
                    'MQTT Inbound E2E Robot',
                    'SN-MQTT-INBOUND-E2E',
                    'ACTIVE',
                    ?
                )
                """,
                ROBOT_ID,
                mqttId,
                ADMIN_ID);
    }

    private void createPatrolAndMission() {
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_area (
                    patrol_area_id,
                    area_code,
                    area_name,
                    boundary,
                    lifecycle_status,
                    created_by,
                    updated_by
                ) VALUES (
                    ?,
                    'Z-99',
                    'MQTT Inbound E2E Area',
                    ST_GeomFromText(
                        'POLYGON(('
                        || '127.02 37.49,'
                        || '127.04 37.49,'
                        || '127.04 37.51,'
                        || '127.02 37.51,'
                        || '127.02 37.49))',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?
                )
                """,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.patrol_route (
                    patrol_route_id,
                    patrol_area_id,
                    route_code,
                    route_name,
                    route_version,
                    route_geometry,
                    lifecycle_status,
                    created_by,
                    updated_by
                ) VALUES (
                    ?,
                    ?,
                    'Z-99-E2E',
                    'MQTT Inbound E2E Route',
                    1,
                    ST_GeomFromText(
                        'LINESTRING('
                        || '127.025 37.495,'
                        || '127.035 37.505)',
                        4326
                    ),
                    'ACTIVE',
                    ?,
                    ?
                )
                """,
                ROUTE_ID,
                AREA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO robot.mission (
                    mission_id,
                    mission_code,
                    robot_id,
                    patrol_area_id,
                    patrol_route_id,
                    status,
                    created_by,
                    updated_by
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'CREATED',
                    ?,
                    ?
                )
                """,
                MISSION_ID,
                MISSION_CODE,
                ROBOT_ID,
                AREA_ID,
                ROUTE_ID,
                ADMIN_ID,
                ADMIN_ID);
    }

    private static void await(
            BooleanSupplier condition,
            AtomicReference<Throwable> callbackFailure
    ) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()
                && callbackFailure.get() == null
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(callbackFailure.get()).isNull();
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void disconnect(MqttAsyncClient client)
            throws MqttException {
        if (client.isConnected()) {
            client.disconnect().waitForCompletion(5_000);
        }
        client.close();
    }

    private static MqttConnectionOptions connectionOptions(
            String broker,
            String caCertificate,
            String username,
            String password
    ) {
        MqttProperties properties = new MqttProperties();
        properties.setNowMqttSecurityStrategy("tls");
        properties.setBroker(broker);
        properties.setClientId("mqtt-inbound-e2e");
        properties.setUsername(username);
        properties.setPassword(password);
        properties.setSubscribeTopic("tbs/v2/robots/+/events/#");
        properties.setPublishTopic(
                "tbs/v2/robots/robot-test/commands/request");
        properties.setQos(1);
        properties.getSecurity().setTrustCertificate(caCertificate);

        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{broker});
        options.setUserName(username);
        options.setPassword(password.getBytes(StandardCharsets.UTF_8));
        options.setSocketFactory(
                new TlsMqttSecurityStrategy(properties)
                        .createSslContext()
                        .getSocketFactory());
        options.setCleanStart(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        return options;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be set for the MQTT Broker E2E test");
        }
        return value;
    }

    private static final class DispatchingCallback implements MqttCallback {

        private final MqttInboundMessageDispatcher dispatcher;
        private final AtomicInteger deliveries;
        private final AtomicReference<Throwable> callbackFailure;

        private DispatchingCallback(
                MqttInboundMessageDispatcher dispatcher,
                AtomicInteger deliveries,
                AtomicReference<Throwable> callbackFailure
        ) {
            this.dispatcher = dispatcher;
            this.deliveries = deliveries;
            this.callbackFailure = callbackFailure;
        }

        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            callbackFailure.compareAndSet(null, exception);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                dispatcher.dispatch(new MqttInboundMessage(
                        topic,
                        new String(
                                message.getPayload(),
                                StandardCharsets.UTF_8),
                        message.getQos(),
                        Instant.now()));
            } catch (Throwable throwable) {
                callbackFailure.compareAndSet(null, throwable);
            } finally {
                deliveries.incrementAndGet();
            }
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

    private static <T extends MqttV2Payload>
            MqttTopicMessageHandler<T> ignoredHandler(
                    MqttTopicType topicType,
                    Class<T> payloadType
            ) {
        return new MqttTopicMessageHandler<>() {
            @Override
            public MqttTopicType topicType() {
                return topicType;
            }

            @Override
            public Class<T> payloadType() {
                return payloadType;
            }

            @Override
            public void handle(ParsedMqttMessage message) {
            }
        };
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            MqttTopicParser.class,
            MqttMessageParser.class,
            MqttInboundFreshnessProperties.class,
            MqttInboundFreshnessGuard.class,
            MqttInboundMessageDispatcher.class,
            MqttReplayProtectionService.class,
            RobotEventIngestionService.class,
            MissionEventIngestionService.class,
            IncidentEventIngestionService.class,
            MissionEventMqttMessageHandler.class,
            IncidentEventMqttMessageHandler.class
    })
    static class TestApplication {

        @Bean
        MqttTopicMessageHandler<PresencePayload> presenceHandler() {
            return ignoredHandler(
                    MqttTopicType.PRESENCE,
                    PresencePayload.class);
        }

        @Bean
        MqttTopicMessageHandler<StatePayload> stateHandler() {
            return ignoredHandler(
                    MqttTopicType.STATE,
                    StatePayload.class);
        }

        @Bean
        MqttTopicMessageHandler<TelemetryPayload> telemetryHandler() {
            return ignoredHandler(
                    MqttTopicType.TELEMETRY,
                    TelemetryPayload.class);
        }

        @Bean
        MqttTopicMessageHandler<MqttV2Payload> batteryHandler() {
            return ignoredHandler(
                    MqttTopicType.BATTERY_EVENT,
                    MqttV2Payload.class);
        }

        @Bean
        MqttTopicMessageHandler<MqttV2Payload> safetyHandler() {
            return ignoredHandler(
                    MqttTopicType.SAFETY_EVENT,
                    MqttV2Payload.class);
        }

        @Bean
        MqttTopicMessageHandler<CommandResultPayload> commandResultHandler() {
            return ignoredHandler(
                    MqttTopicType.COMMAND_RESULT,
                    CommandResultPayload.class);
        }
    }
}
