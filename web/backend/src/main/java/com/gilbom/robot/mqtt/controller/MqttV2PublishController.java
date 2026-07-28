package com.gilbom.robot.mqtt.controller;

import com.gilbom.robot.mqtt.client.MqttV2Publisher;
import com.gilbom.robot.data.api.response.MqttPublishResponse;
import com.gilbom.robot.data.mqtt.v2.outbound.CommandRequestPayload;
import com.gilbom.robot.data.mqtt.v2.outbound.IngestReceiptPayload;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/mqtt/v2/robots/{mqttId}")
public class MqttV2PublishController {

    private final MqttV2Publisher mqttPublisher;

    public MqttV2PublishController(MqttV2Publisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }
    /*For 조종 명령*/
    @PostMapping("/commands/request")
    public ResponseEntity<MqttPublishResponse> publishCommand(
            @PathVariable String mqttId,
            @Valid @RequestBody CommandRequestPayload payload
    ) {
        return accepted(mqttPublisher.publishCommand(mqttId, payload));
    }
    
    /*For 중복 탐사 방지*/
    @PostMapping("/ingest/receipt")
    public ResponseEntity<MqttPublishResponse> publishIngestReceipt(
            @PathVariable String mqttId,
            @Valid @RequestBody IngestReceiptPayload payload
    ) {
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
