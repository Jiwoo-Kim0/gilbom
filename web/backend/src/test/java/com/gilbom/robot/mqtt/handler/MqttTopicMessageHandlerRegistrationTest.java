package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.inbound.BatteryEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.CommandResultPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.IncidentEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.MissionEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.PresencePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.SafetyEventPayload;
import com.gilbom.robot.data.mqtt.v2.inbound.StatePayload;
import com.gilbom.robot.data.mqtt.v2.inbound.TelemetryPayload;
import com.gilbom.robot.command.RobotCommandResultIngestionService;
import com.gilbom.defect.IncidentEventIngestionService;
import com.gilbom.robot.event.RobotEventIngestionService;
import com.gilbom.robot.mission.MissionEventIngestionService;
import com.gilbom.robot.mqtt.topic.MqttTopicType;
import com.gilbom.robot.mqtt.receipt.IngestReceiptCoordinator;
import com.gilbom.robot.status.RobotStatusIngestionService;
import com.gilbom.robot.telemetry.TelemetryIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

class MqttTopicMessageHandlerRegistrationTest {

    @Test
    void bindsEachInboundTopicToItsTypedHandler() {
        List<MqttTopicMessageHandler<?>> handlers = List.of(
                new PresenceMqttMessageHandler(
                        mock(RobotStatusIngestionService.class)),
                new StateMqttMessageHandler(
                        mock(RobotStatusIngestionService.class)),
                new TelemetryMqttMessageHandler(
                        mock(TelemetryIngestionService.class)),
                new IncidentEventMqttMessageHandler(
                        mock(IncidentEventIngestionService.class),
                        mock(IngestReceiptCoordinator.class)),
                new BatteryEventMqttMessageHandler(
                        mock(RobotEventIngestionService.class),
                        mock(IngestReceiptCoordinator.class)),
                new SafetyEventMqttMessageHandler(
                        mock(RobotEventIngestionService.class),
                        mock(IngestReceiptCoordinator.class)),
                new MissionEventMqttMessageHandler(
                        mock(MissionEventIngestionService.class),
                        mock(IngestReceiptCoordinator.class)),
                new CommandResultMqttMessageHandler(
                        mock(RobotCommandResultIngestionService.class))
        );

        assertThat(handlers)
                .extracting(
                        MqttTopicMessageHandler::topicType,
                        MqttTopicMessageHandler::payloadType
                )
                .containsExactlyInAnyOrder(
                        tuple(MqttTopicType.PRESENCE, PresencePayload.class),
                        tuple(MqttTopicType.STATE, StatePayload.class),
                        tuple(MqttTopicType.TELEMETRY, TelemetryPayload.class),
                        tuple(
                                MqttTopicType.INCIDENT_EVENT,
                                IncidentEventPayload.class
                        ),
                        tuple(
                                MqttTopicType.BATTERY_EVENT,
                                BatteryEventPayload.class
                        ),
                        tuple(
                                MqttTopicType.SAFETY_EVENT,
                                SafetyEventPayload.class
                        ),
                        tuple(
                                MqttTopicType.MISSION_EVENT,
                                MissionEventPayload.class
                        ),
                        tuple(
                                MqttTopicType.COMMAND_RESULT,
                                CommandResultPayload.class
                        )
                );
    }
}
