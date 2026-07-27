package com.gilbom.robot.mqtt.handler;

import com.gilbom.robot.data.mqtt.v2.common.MqttV2Payload;
import com.gilbom.robot.mqtt.parser.ParsedMqttMessage;
import com.gilbom.robot.mqtt.topic.MqttTopicType;

public interface MqttTopicMessageHandler<T extends MqttV2Payload> {

    MqttTopicType topicType();

    Class<T> payloadType();

    void handle(ParsedMqttMessage message);
}
