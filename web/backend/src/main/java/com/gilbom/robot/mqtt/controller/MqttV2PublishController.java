package com.gilbom.robot.mqtt.controller;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.application.RobotScopeService;
import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.data.api.response.MqttPublishResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/mqtt/v2/robots/{robotId}")
public class MqttV2PublishController {

    private final MqttV2Publisher mqttPublisher;
    private final RobotScopeService scopeService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public MqttV2PublishController(
            MqttV2Publisher mqttPublisher,
            RobotScopeService scopeService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.mqttPublisher = mqttPublisher;
        this.scopeService = scopeService;
        this.userIdResolver = userIdResolver;
    }
    /*For 조종 명령*/
    @PostMapping("/commands/request")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_EXECUTE')")
    public ResponseEntity<MqttPublishResponse> publishCommand(
            @PathVariable String robotId,
            @Valid @RequestBody CommandRequestPayload payload,
            Authentication authentication
    ) {
        String mqttId = scopeService.requireOperableMqttId(
                userIdResolver.require(authentication),
                robotId
        );
        return accepted(mqttPublisher.publishCommand(mqttId, payload));
    }
    
    /*For 중복 탐사 방지*/
    @PostMapping("/ingest/receipt")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_EXECUTE')")
    public ResponseEntity<MqttPublishResponse> publishIngestReceipt(
            @PathVariable String robotId,
            @Valid @RequestBody IngestReceiptPayload payload,
            Authentication authentication
    ) {
        String mqttId = scopeService.requireOperableMqttId(
                userIdResolver.require(authentication),
                robotId
        );
        return accepted(mqttPublisher.publishIngestReceipt(mqttId, payload));
    }

    
    
    
    
    
    /*For Common*/
    private ResponseEntity<MqttPublishResponse> accepted(
            MqttV2Publisher.PublishResult result
    ) {
        return ResponseEntity.accepted().body(new MqttPublishResponse(
                result.topic(),
                result.qos(),
                result.retained(),
                true
        ));
    }

}
