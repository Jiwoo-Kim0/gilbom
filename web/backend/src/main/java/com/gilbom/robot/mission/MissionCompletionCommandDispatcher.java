package com.gilbom.robot.mission;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.gilbom.common.error.BusinessException;
import com.gilbom.robot.command.RobotCommandLifecycleService;
import com.gilbom.robot.command.RobotCommandRecord;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;

@Service
public class MissionCompletionCommandDispatcher {

    private static final String SYSTEM_ACTOR_ID = "MISSION-SCHEDULER";

    private final MissionCompletionCommandCreationService creationService;
    private final RobotCommandLifecycleService lifecycleService;
    private final MqttV2Publisher mqttPublisher;

    public MissionCompletionCommandDispatcher(
            MissionCompletionCommandCreationService creationService,
            RobotCommandLifecycleService lifecycleService,
            MqttV2Publisher mqttPublisher
    ) {
        this.creationService = creationService;
        this.lifecycleService = lifecycleService;
        this.mqttPublisher = mqttPublisher;
    }

    public int dispatchDueMissions(Instant detectedAt) {
        List<RobotCommandRecord> commands =
                creationService.createDueCommands(detectedAt);
        for (RobotCommandRecord command : commands) {
            publish(command, detectedAt);
        }
        return commands.size();
    }

    private void publish(RobotCommandRecord command, Instant attemptedAt) {
        CommandRequestPayload payload = new CommandRequestPayload(
                "2.0",
                command.commandId(),
                command.commandType(),
                command.issuedAt(),
                command.expiresAt(),
                new CommandRequestPayload.RequestedBy(
                        CommandRequestPayload.ActorType.SYSTEM,
                        SYSTEM_ACTOR_ID),
                new CommandRequestPayload.Parameters(
                        command.parameters().missionId(),
                        command.parameters().zoneId(),
                        command.parameters().reason(),
                        command.parameters().completionReason()));
        try {
            mqttPublisher.publishCommand(command.mqttId(), payload);
            lifecycleService.markSent(command.commandId(), attemptedAt);
        } catch (BusinessException exception) {
            lifecycleService.markPublishFailed(
                    command.commandId(),
                    attemptedAt,
                    exception.errorCode().name());
        } catch (RuntimeException exception) {
            lifecycleService.markPublishFailed(
                    command.commandId(),
                    attemptedAt,
                    "MQTT_PUBLISH_FAILED");
        }
    }
}
