package com.gilbom.robot.mqtt.contract.v2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum EventSource {
    AI("ai"),
    POWER("power"),
    SAFETY("safety"),
    MISSION("mission");

    private final String value;

    EventSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EventSource fromValue(String value) {
        return Arrays.stream(values())
                .filter(source -> source.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported event source"
                ));
    }
}
