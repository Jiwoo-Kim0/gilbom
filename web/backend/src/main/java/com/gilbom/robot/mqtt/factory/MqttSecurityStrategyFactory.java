package com.gilbom.robot.mqtt.factory;

import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.strategy.MqttSecurityStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class MqttSecurityStrategyFactory {

    private final String nowStrategy;
    private final List<MqttSecurityStrategy> strategies;

    public MqttSecurityStrategyFactory(
            MqttProperties properties,
            List<MqttSecurityStrategy> strategies
    ) {
        this.nowStrategy = properties.getNowMqttSecurityStrategy();
        this.strategies = List.copyOf(strategies);
    }

    public MqttSecurityStrategy getNowStrategy() {
        String normalizedStrategy = nowStrategy == null
                ? ""
                : nowStrategy.trim().toLowerCase(Locale.ROOT);

        return strategies
        		.stream()
                .filter(strategy -> strategy.mode().equalsIgnoreCase(normalizedStrategy))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported MQTT security strategy: '" + nowStrategy
                                + "'. Supported values are: " + supportedModes()
                ));
    }

    public String getNowStrategyName() {
        return nowStrategy;
    }

    private String supportedModes() {
        return strategies.stream()
                .map(MqttSecurityStrategy::mode)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
