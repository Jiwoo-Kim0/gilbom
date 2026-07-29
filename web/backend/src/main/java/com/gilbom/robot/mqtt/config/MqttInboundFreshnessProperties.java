package com.gilbom.robot.mqtt.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mqtt.inbound-freshness")
public class MqttInboundFreshnessProperties {

    private Duration telemetryMaxDelay = Duration.ofSeconds(60);
    private Duration stateMaxDelay = Duration.ofSeconds(300);
    private Duration commandResultMaxDelay = Duration.ofSeconds(60);
    private Duration futureTolerance = Duration.ofSeconds(5);

    public Duration getTelemetryMaxDelay() {
        return telemetryMaxDelay;
    }

    public void setTelemetryMaxDelay(Duration telemetryMaxDelay) {
        this.telemetryMaxDelay = telemetryMaxDelay;
    }

    public Duration getStateMaxDelay() {
        return stateMaxDelay;
    }

    public void setStateMaxDelay(Duration stateMaxDelay) {
        this.stateMaxDelay = stateMaxDelay;
    }

    public Duration getCommandResultMaxDelay() {
        return commandResultMaxDelay;
    }

    public void setCommandResultMaxDelay(
            Duration commandResultMaxDelay
    ) {
        this.commandResultMaxDelay = commandResultMaxDelay;
    }

    public Duration getFutureTolerance() {
        return futureTolerance;
    }

    public void setFutureTolerance(Duration futureTolerance) {
        this.futureTolerance = futureTolerance;
    }
}
