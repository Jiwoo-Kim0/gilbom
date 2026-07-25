package com.gilbom.robot.mqtt.controller;

import com.gilbom.robot.mqtt.client.MqttPublisher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/mqtt/test")
public class MqttTestController {

    private final MqttPublisher mqttPublisher;

    public MqttTestController(MqttPublisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }

    @PostMapping("/publish")
    public ResponseEntity<PublishResponse> publish(@Valid @RequestBody PublishRequest request) {
        boolean accepted = mqttPublisher.publish(request.topic(), request.payload());
        PublishResponse response = new PublishResponse(request.topic(), accepted);

        if (!accepted) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
        return ResponseEntity.ok(response);
    }

    public record PublishRequest(
            @NotBlank String topic,
            @NotBlank String payload
    ) {
    }

    public record PublishResponse(
            String topic,
            boolean accepted
    ) {
    }
}
