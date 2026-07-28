package com.gilbom.robot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.session.AuthenticationSessionConfiguration;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.command.RobotCommandCreationService;
import com.gilbom.robot.command.RobotCommandLifecycleService;
import com.gilbom.robot.command.RobotCommandQueryService;
import com.gilbom.robot.command.RobotCommandRepository;
import com.gilbom.robot.command.RobotCommandResultIngestionService;
import com.gilbom.robot.command.RobotCommandService;
import com.gilbom.robot.command.RobotCommandStatus;
import com.gilbom.robot.command.RobotCommandTimeoutService;
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.api.response.RobotCommandResponse;
import com.gilbom.robot.data.mqtt.v2.common.RobotMode;
import com.gilbom.robot.data.mqtt.v2.common.RobotStateReason;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.realtime.RobotRealtimeEventListener;
import com.gilbom.robot.realtime.RobotSseService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = RobotCommandLifecycleIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.task.scheduling.enabled=false"
        })
class RobotCommandLifecycleIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID USER_ID =
            UUID.fromString("19000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("19000000-0000-4000-8000-000000000002");
    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_robot_command_test")
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
    private RobotCommandService commandService;

    @Autowired
    private RobotCommandResultIngestionService resultService;

    @Autowired
    private RobotCommandTimeoutService timeoutService;

    @Autowired
    private RobotCommandQueryService queryService;

    @MockitoBean
    private MqttV2Publisher mqttPublisher;

    @MockitoBean
    private RobotSseService sseService;

    private UUID robotId;
    private String mqttId;

    @BeforeEach
    void setUp() {
        robotId = UUID.randomUUID();
        mqttId = "RBT-" + robotId.toString()
                .substring(0, 8)
                .toUpperCase(java.util.Locale.ROOT);
        createUser(USER_ID, "COMMAND-OPERATOR");
        createUser(OTHER_USER_ID, "COMMAND-OTHER");
        jdbcTemplate.update("""
                INSERT INTO robot.robot (
                    robot_id,
                    mqtt_id,
                    robot_name,
                    serial_number,
                    operational_status,
                    registered_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (robot_id) DO NOTHING
                """,
                robotId,
                mqttId,
                "Command Robot",
                "SN-COMMAND-" + robotId,
                USER_ID);
        jdbcTemplate.update("""
                INSERT INTO auth.user_robot_assignment (
                    user_id,
                    robot_id,
                    assignment_type,
                    reason,
                    assigned_by
                ) VALUES (?, ?, 'OPERATOR', 'command integration test', ?)
                ON CONFLICT (user_id, robot_id)
                    WHERE is_active = TRUE
                    DO NOTHING
                """,
                USER_ID,
                robotId,
                USER_ID);
        clearInvocations(mqttPublisher, sseService);
        when(mqttPublisher.publishCommand(eq(mqttId), any()))
                .thenReturn(new MqttV2Publisher.PublishResult(
                        "tbs/v2/robots/" + mqttId + "/commands/request",
                        1,
                        false));
    }

    @Test
    void createsPublishesAuditsAndReplaysWithoutDuplicatePublish() {
        UUID idempotencyKey = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        RobotCommandResponse first = create(
                emergencyStop(),
                idempotencyKey,
                requestId);
        RobotCommandResponse replay = create(
                emergencyStop(),
                idempotencyKey,
                UUID.randomUUID());

        assertThat(first.commandStatus()).isEqualTo("SENT");
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.commandStatus()).isEqualTo("SENT");
        assertThat(first.publishAttemptCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_command
                WHERE command_id = ?
                """, Integer.class, first.commandId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE request_id = ?
                  AND action_type = 'ROBOT_COMMAND_ISSUED'
                  AND target_type = 'ROBOT_COMMAND'
                  AND target_id = ?
                """, Integer.class, requestId, first.commandId()))
                .isEqualTo(1);
        assertThat(queryService.findCommands(
                USER_ID,
                robotId,
                0,
                20).content())
                .extracting(RobotCommandResponse::commandId)
                .containsExactly(first.commandId());
        assertThat(queryService.findCommand(
                USER_ID,
                first.commandId()).commandStatus())
                .isEqualTo("SENT");
        assertThatThrownBy(() -> queryService.findCommand(
                OTHER_USER_ID,
                first.commandId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.COMMAND_NOT_FOUND));
        verify(mqttPublisher).publishCommand(eq(mqttId), any());
        verifyNoMoreInteractions(mqttPublisher);
    }

    @Test
    void storesAcceptedAndFinalResultsAndDeduplicatesResultId() {
        RobotCommandResponse command = create(
                emergencyStop(),
                UUID.randomUUID(),
                UUID.randomUUID());
        Instant acceptedAt = Instant.now().plusSeconds(1);
        UUID acceptedResultId = UUID.randomUUID();
        CommandResultPayload accepted = result(
                acceptedResultId,
                command.commandId(),
                CommandResultPayload.Status.ACCEPTED,
                acceptedAt,
                null);

        var first = resultService.ingest(
                accepted,
                raw(accepted),
                acceptedAt.plusMillis(100));
        var duplicate = resultService.ingest(
                accepted,
                raw(accepted),
                acceptedAt.plusMillis(200));
        assertThatThrownBy(() -> resultService.ingest(
                result(
                        acceptedResultId,
                        command.commandId(),
                        CommandResultPayload.Status.COMPLETED,
                        acceptedAt,
                        acceptedAt.plusSeconds(1)),
                "{\"status\":\"COMPLETED\"}",
                acceptedAt.plusSeconds(1)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.COMMON_CONFLICT));
        Instant completedAt = acceptedAt.plusSeconds(2);
        var completed = resultService.ingest(
                result(
                        UUID.randomUUID(),
                        command.commandId(),
                        CommandResultPayload.Status.COMPLETED,
                        acceptedAt,
                        completedAt),
                "{\"status\":\"COMPLETED\"}",
                completedAt.plusMillis(100));

        assertThat(first.stored()).isTrue();
        assertThat(first.commandStatus())
                .isEqualTo(RobotCommandStatus.ACCEPTED);
        assertThat(duplicate.stored()).isFalse();
        assertThat(completed.commandStatus())
                .isEqualTo(RobotCommandStatus.COMPLETED);
        assertThat(commandStatus(command.commandId()))
                .isEqualTo("COMPLETED");
        assertThat(resultCount(command.commandId())).isEqualTo(2);
    }

    @Test
    void expiresPendingCommandAndStoresLateResultWithoutRollingBackStatus() {
        RobotCommandResponse command = create(
                emergencyStop(),
                UUID.randomUUID(),
                UUID.randomUUID());
        Instant detectedAt = command.expiresAt().plusSeconds(1);

        assertThat(timeoutService.expireCommands(detectedAt))
                .isGreaterThanOrEqualTo(1);
        var late = resultService.ingest(
                result(
                        UUID.randomUUID(),
                        command.commandId(),
                        CommandResultPayload.Status.COMPLETED,
                        command.issuedAt(),
                        command.expiresAt().plusSeconds(2)),
                "{\"status\":\"COMPLETED\"}",
                command.expiresAt().plusSeconds(3));

        assertThat(late.stored()).isTrue();
        assertThat(late.commandStatus())
                .isEqualTo(RobotCommandStatus.EXPIRED);
        assertThat(late.commandStatusChanged()).isFalse();
        assertThat(commandStatus(command.commandId()))
                .isEqualTo("EXPIRED");
        assertThat(resultCount(command.commandId())).isEqualTo(1);
    }

    @Test
    void lateResultCannotCompleteCommandBeforeTimeoutScannerRuns() {
        RobotCommandResponse command = create(
                emergencyStop(),
                UUID.randomUUID(),
                UUID.randomUUID());

        var late = resultService.ingest(
                result(
                        UUID.randomUUID(),
                        command.commandId(),
                        CommandResultPayload.Status.COMPLETED,
                        command.issuedAt(),
                        command.expiresAt().plusSeconds(1)),
                "{\"status\":\"COMPLETED\"}",
                command.expiresAt().plusSeconds(2));

        assertThat(late.stored()).isTrue();
        assertThat(late.commandStatusChanged()).isTrue();
        assertThat(late.commandStatus())
                .isEqualTo(RobotCommandStatus.EXPIRED);
        assertThat(commandStatus(command.commandId()))
                .isEqualTo("EXPIRED");
        timeoutService.expireCommands(command.expiresAt().plusSeconds(3));
        assertThat(commandStatus(command.commandId()))
                .isEqualTo("EXPIRED");
    }

    @Test
    void recordsPublishFailureInsteadOfLosingCreatedCommand() {
        doThrow(new BusinessException(ErrorCode.MQTT_UNAVAILABLE))
                .when(mqttPublisher)
                .publishCommand(eq(mqttId), any());

        RobotCommandResponse response = create(
                emergencyStop(),
                UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(response.commandStatus())
                .isEqualTo("PUBLISH_FAILED");
        assertThat(response.lastErrorCode())
                .isEqualTo("MQTT_UNAVAILABLE");
        assertThat(response.publishAttemptCount()).isEqualTo(1);
    }

    @Test
    void appliesFailedAndRejectedFinalResults() {
        for (CommandResultPayload.Status status : new CommandResultPayload.Status[]{
                CommandResultPayload.Status.FAILED,
                CommandResultPayload.Status.REJECTED}) {
            RobotCommandResponse command = create(
                    emergencyStop(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
            Instant completedAt = command.issuedAt().plusSeconds(1);

            resultService.ingest(
                    result(
                            UUID.randomUUID(),
                            command.commandId(),
                            status,
                            command.issuedAt(),
                            completedAt),
                    "{\"status\":\"" + status + "\"}",
                    completedAt.plusMillis(100));

            assertThat(commandStatus(command.commandId()))
                    .isEqualTo(status.name());
        }
    }

    @Test
    void rollsBackCommandAndIdempotencyClaimWhenAuditFails() {
        int commandCountBefore = tableCount("robot.robot_command");
        int requestCountBefore = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                WHERE request_path LIKE '/api/v1/robots/%/commands'
                """, Integer.class);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION audit.reject_command_for_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'ROBOT_COMMAND_ISSUED' THEN
                        RAISE EXCEPTION 'forced command audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_command_audit_for_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_command_for_test()
                """);
        try {
            assertThatThrownBy(() -> create(
                    emergencyStop(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS
                    tr_reject_command_audit_for_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION IF EXISTS
                    audit.reject_command_for_test()
                    """);
        }

        assertThat(tableCount("robot.robot_command"))
                .isEqualTo(commandCountBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                WHERE request_path LIKE '/api/v1/robots/%/commands'
                """, Integer.class))
                .isEqualTo(requestCountBefore);
        verifyNoMoreInteractions(mqttPublisher);
    }

    @Test
    void rejectsOperatorWithoutRobotScopeAsNotFound() {
        assertThatThrownBy(() -> commandService.createAndPublish(
                robotId,
                emergencyStop(),
                OTHER_USER_ID,
                UUID.randomUUID(),
                AuditRequestContext.requestIdOnly(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ROBOT_NOT_FOUND));
    }

    private RobotCommandResponse create(
            RobotCommandCreateRequest request,
            UUID idempotencyKey,
            UUID requestId
    ) {
        return commandService.createAndPublish(
                robotId,
                request,
                USER_ID,
                idempotencyKey,
                AuditRequestContext.requestIdOnly(requestId));
    }

    private RobotCommandCreateRequest emergencyStop() {
        return new RobotCommandCreateRequest(
                CommandRequestPayload.CommandType.EMERGENCY_STOP,
                new RobotCommandCreateRequest.Parameters(
                        null,
                        null,
                        "integration test emergency stop"));
    }

    private CommandResultPayload result(
            UUID resultId,
            UUID commandId,
            CommandResultPayload.Status status,
            Instant receivedAt,
            Instant completedAt
    ) {
        return new CommandResultPayload(
                "2.0",
                resultId,
                commandId,
                mqttId,
                status,
                receivedAt,
                completedAt,
                "NONE",
                new CommandResultPayload.State(
                        RobotMode.SAFE_STOP,
                        RobotStateReason.REMOTE_ESTOP));
    }

    private String raw(CommandResultPayload payload) {
        return """
                {
                  "schema_version":"2.0",
                  "result_id":"%s",
                  "command_id":"%s",
                  "mqtt_id":"%s",
                  "status":"%s"
                }
                """.formatted(
                payload.resultId(),
                payload.commandId(),
                payload.mqttId(),
                payload.status());
    }

    private String commandStatus(UUID commandId) {
        return jdbcTemplate.queryForObject("""
                SELECT command_status
                FROM robot.robot_command
                WHERE command_id = ?
                """, String.class, commandId);
    }

    private int resultCount(UUID commandId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM robot.robot_command_result
                WHERE command_id = ?
                """, Integer.class, commandId);
    }

    private int tableCount(String qualifiedTableName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTableName,
                Integer.class);
    }

    private void createUser(UUID userId, String userCode) {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    account_status,
                    is_active,
                    password_change_required,
                    password_changed_at,
                    account_origin
                ) VALUES (
                    ?,
                    ?,
                    '{argon2}$argon2id$command-integration',
                    ?,
                    ?,
                    'ACTIVE',
                    TRUE,
                    FALSE,
                    NOW(),
                    'ADMIN_CREATED'
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId,
                userCode,
                userCode,
                userCode.toLowerCase() + "@example.test");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AuthenticationSessionConfiguration.class,
            AuditLogRepository.class,
            AuditLogService.class,
            BestEffortAuditLogWriter.class,
            RobotScopeService.class,
            RobotCommandRepository.class,
            RobotCommandCreationService.class,
            RobotCommandLifecycleService.class,
            RobotCommandService.class,
            RobotCommandQueryService.class,
            RobotCommandResultIngestionService.class,
            RobotCommandTimeoutService.class,
            RobotRealtimeEventListener.class
    })
    static class TestApplication {
    }
}
