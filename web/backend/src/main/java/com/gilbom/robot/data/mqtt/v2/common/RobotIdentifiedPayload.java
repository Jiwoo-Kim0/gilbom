package com.gilbom.robot.data.mqtt.v2.common;

public interface RobotIdentifiedPayload extends MqttV2Payload {

    String robotId();
}
