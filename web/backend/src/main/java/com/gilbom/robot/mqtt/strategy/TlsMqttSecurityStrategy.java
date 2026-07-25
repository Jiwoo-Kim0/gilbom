package com.gilbom.robot.mqtt.strategy;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.config.MqttProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

@Component
public class TlsMqttSecurityStrategy implements MqttSecurityStrategy {

    private final MqttProperties properties;

    public TlsMqttSecurityStrategy(MqttProperties properties) {
        this.properties = properties;
    }

    @Override
    public String mode() {
        return "tls";
    }

    @Override
    public SSLContext createSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    null,
                    PemSslContextSupport.createTrustManagers(
                            properties.getSecurity().getTrustCertificate()
                    ),
                    new SecureRandom()
            );
            return context;
        } catch (GeneralSecurityException | IOException exception) {
            throw new BusinessException(
                    ErrorCode.MQTT_SECURITY_CONFIGURATION_INVALID,
                    exception
            );
        }
    }
}
