package com.gilbom.robot.mqtt.contract.v2;

public interface RobotIdentifiedPayload extends MqttV2Payload {

    String robotId();
}
