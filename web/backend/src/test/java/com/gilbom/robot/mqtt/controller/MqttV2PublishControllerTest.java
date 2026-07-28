package com.gilbom.robot.mqtt.controller;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.data.api.response.MqttPublishResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MqttV2PublishControllerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-26T05:00:00Z");
    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000031");

    @Test
    void publishesCommandRequest() {
        MqttV2Publisher publisher = mock(MqttV2Publisher.class);
        RobotScopeService scopeService = mock(RobotScopeService.class);
        AuthenticatedUserIdResolver userIdResolver =
                mock(AuthenticatedUserIdResolver.class);
        Authentication authentication = mock(Authentication.class);
        MqttV2PublishController controller =
                new MqttV2PublishController(
                        publisher,
                        scopeService,
                        userIdResolver
                );
        CommandRequestPayload payload = commandPayload();
        MqttV2Publisher.PublishResult result = new MqttV2Publisher.PublishResult(
                "tbs/v2/robots/RBT-001/commands/request",
                1,
                false
        );
        when(userIdResolver.require(authentication)).thenReturn(USER_ID);
        when(scopeService.requireOperableMqttId(USER_ID, "RBT-001"))
                .thenReturn("RBT-001");
        when(publisher.publishCommand("RBT-001", payload)).thenReturn(result);

        var response = controller.publishCommand(
                "RBT-001",
                payload,
                authentication
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(
                new MqttPublishResponse(
                        result.topic(),
                        result.qos(),
                        result.retained(),
                        true
                )
        );
        verify(scopeService).requireOperableMqttId(USER_ID, "RBT-001");
        verify(publisher).publishCommand("RBT-001", payload);
    }

    @Test
    void publishesIngestReceipt() {
        MqttV2Publisher publisher = mock(MqttV2Publisher.class);
        RobotScopeService scopeService = mock(RobotScopeService.class);
        AuthenticatedUserIdResolver userIdResolver =
                mock(AuthenticatedUserIdResolver.class);
        Authentication authentication = mock(Authentication.class);
        MqttV2PublishController controller =
                new MqttV2PublishController(
                        publisher,
                        scopeService,
                        userIdResolver
                );
        IngestReceiptPayload payload = new IngestReceiptPayload(
                "2.0",
                UUID.fromString("93bd80e4-082f-4578-92b1-8ad95ad39ca3"),
                IngestReceiptPayload.Status.STORED,
                "INC-001",
                ISSUED_AT
        );
        MqttV2Publisher.PublishResult result = new MqttV2Publisher.PublishResult(
                "tbs/v2/robots/RBT-001/ingest/receipt",
                1,
                false
        );
        when(userIdResolver.require(authentication)).thenReturn(USER_ID);
        when(scopeService.requireOperableMqttId(USER_ID, "RBT-001"))
                .thenReturn("RBT-001");
        when(publisher.publishIngestReceipt("RBT-001", payload))
                .thenReturn(result);

        var response = controller.publishIngestReceipt(
                "RBT-001",
                payload,
                authentication
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().topic()).isEqualTo(result.topic());
        verify(scopeService).requireOperableMqttId(USER_ID, "RBT-001");
        verify(publisher).publishIngestReceipt("RBT-001", payload);
    }

    @Test
    void hidesOutOfScopeRobotBeforePublishingCommand() {
        MqttV2Publisher publisher = mock(MqttV2Publisher.class);
        RobotScopeService scopeService = mock(RobotScopeService.class);
        AuthenticatedUserIdResolver userIdResolver =
                mock(AuthenticatedUserIdResolver.class);
        Authentication authentication = mock(Authentication.class);
        MqttV2PublishController controller =
                new MqttV2PublishController(
                        publisher,
                        scopeService,
                        userIdResolver
                );
        when(userIdResolver.require(authentication)).thenReturn(USER_ID);
        when(scopeService.requireOperableMqttId(USER_ID, "RBT-HIDDEN"))
                .thenThrow(new BusinessException(ErrorCode.ROBOT_NOT_FOUND));

        assertThatThrownBy(() -> controller.publishCommand(
                "RBT-HIDDEN",
                commandPayload(),
                authentication
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.ROBOT_NOT_FOUND)
        );
        verifyNoInteractions(publisher);
    }

    @Test
    void isAvailableOnlyWithLocalProfile() {
        Profile profile = MqttV2PublishController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }

    private CommandRequestPayload commandPayload() {
        return new CommandRequestPayload(
                "2.0",
                UUID.fromString("726b72a3-e18b-49bf-a864-0a2954624c0e"),
                CommandRequestPayload.CommandType.EMERGENCY_STOP,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(30),
                new CommandRequestPayload.RequestedBy(
                        CommandRequestPayload.ActorType.ADMIN,
                        "admin-001"
                ),
                new CommandRequestPayload.Parameters(
                        null,
                        null,
                        "operator requested emergency stop"
                )
        );
    }
}
