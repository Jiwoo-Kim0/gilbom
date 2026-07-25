package com.gilbom.robot.mqtt.strategy;

import javax.net.ssl.SSLContext;

public interface MqttSecurityStrategy {

    String mode();

    SSLContext createSslContext();
}
