package com.gilbom.robot.command;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.common.error.BusinessException;
import com.gilbom.robot.command.RobotCommandCreationService.CreatedCommand;
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.api.response.RobotCommandResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;

@Service
public class RobotCommandService {

    private final RobotCommandCreationService creationService;
    private final RobotCommandLifecycleService lifecycleService;
    private final MqttV2Publisher mqttPublisher;
    private final Clock clock;

    public RobotCommandService(
            RobotCommandCreationService creationService,
            RobotCommandLifecycleService lifecycleService,
            MqttV2Publisher mqttPublisher,
            Clock clock
    ) {
        this.creationService = creationService;
        this.lifecycleService = lifecycleService;
        this.mqttPublisher = mqttPublisher;
        this.clock = clock;
    }

    public RobotCommandResponse createAndPublish(
            UUID robotId,
            RobotCommandCreateRequest request,
            UUID actorUserId,
            UUID idempotencyKey,
            AuditRequestContext requestContext
    ) {
        CreatedCommand created = creationService.create(
                robotId,
                request,
                actorUserId,
                idempotencyKey,
                requestContext);
        if (!created.newlyCreated()) {
            return RobotCommandQueryService.toResponse(created.command());
        }

        RobotCommandRecord command = created.command();
        CommandRequestPayload payload = new CommandRequestPayload(
                "2.0",
                command.commandId(),
                command.commandType(),
                command.issuedAt(),
                command.expiresAt(),
                new CommandRequestPayload.RequestedBy(
                        CommandRequestPayload.ActorType.ADMIN,
                        command.requestedByUserCode()),
                new CommandRequestPayload.Parameters(
                        command.parameters().missionId(),
                        command.parameters().zoneId(),
                        command.parameters().reason(),
                        command.parameters().completionReason()));
        try {
            mqttPublisher.publishCommand(command.mqttId(), payload);
            command = lifecycleService.markSent(
                    command.commandId(),
                    clock.instant());
        } catch (BusinessException exception) {
            command = lifecycleService.markPublishFailed(
                    command.commandId(),
                    clock.instant(),
                    exception.errorCode().name());
        } catch (RuntimeException exception) {
            command = lifecycleService.markPublishFailed(
                    command.commandId(),
                    clock.instant(),
                    "MQTT_PUBLISH_FAILED");
        }
        return RobotCommandQueryService.toResponse(command);
    }
}
