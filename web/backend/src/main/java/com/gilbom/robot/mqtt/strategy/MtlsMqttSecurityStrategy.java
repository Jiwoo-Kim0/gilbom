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
public class MtlsMqttSecurityStrategy implements MqttSecurityStrategy {

    private final MqttProperties properties;

    public MtlsMqttSecurityStrategy(MqttProperties properties) {
        this.properties = properties;
    }

    @Override
    public String mode() {
        return "mtls";
    }

    @Override
    public SSLContext createSslContext() {
        try {
            MqttProperties.Security security = properties.getSecurity();
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    PemSslContextSupport.createKeyManagers(
                            security.getClientCertificate(),
                            security.getClientPrivateKey()
                    ),
                    PemSslContextSupport.createTrustManagers(security.getTrustCertificate()),
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
