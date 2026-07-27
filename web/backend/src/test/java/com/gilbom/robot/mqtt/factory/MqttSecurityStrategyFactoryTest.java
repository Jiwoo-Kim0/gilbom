package com.gilbom.robot.mqtt.factory;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.strategy.MtlsMqttSecurityStrategy;
import com.gilbom.robot.mqtt.strategy.TlsMqttSecurityStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttSecurityStrategyFactoryTest {

    @Test
    void selectsTlsStrategy() {
        MqttProperties properties = propertiesWithStrategy("tls");
        MqttSecurityStrategyFactory factory = factory(properties);

        assertThat(factory.getNowStrategy()).isInstanceOf(TlsMqttSecurityStrategy.class);
    }

    @Test
    void selectsMtlsStrategyIgnoringCaseAndWhitespace() {
        MqttProperties properties = propertiesWithStrategy("  MTLS ");
        MqttSecurityStrategyFactory factory = factory(properties);

        assertThat(factory.getNowStrategy()).isInstanceOf(MtlsMqttSecurityStrategy.class);
    }

    @Test
    void rejectsUnsupportedStrategy() {
        MqttProperties properties = propertiesWithStrategy("stl");
        MqttSecurityStrategyFactory factory = factory(properties);

        assertThatThrownBy(factory::getNowStrategy)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mtls, tls");
    }

    @Test
    void mapsMissingTlsCertificateToSecurityConfigurationErrorCode() {
        MqttProperties properties = propertiesWithStrategy("tls");
        properties.getSecurity().setTrustCertificate("build/missing-mqtt-ca.pem");

        assertThatThrownBy(() -> new TlsMqttSecurityStrategy(properties).createSslContext())
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MQTT_SECURITY_CONFIGURATION_INVALID)
                );
    }

    @Test
    void mapsMissingMtlsPathsToSecurityConfigurationErrorCode() {
        MqttProperties properties = propertiesWithStrategy("mtls");

        assertThatThrownBy(() -> new MtlsMqttSecurityStrategy(properties).createSslContext())
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.MQTT_SECURITY_CONFIGURATION_INVALID)
                );
    }

    private MqttSecurityStrategyFactory factory(MqttProperties properties) {
        TlsMqttSecurityStrategy tlsStrategy = new TlsMqttSecurityStrategy(properties);
        MtlsMqttSecurityStrategy mtlsStrategy = new MtlsMqttSecurityStrategy(properties);

        return new MqttSecurityStrategyFactory(
                properties,
                List.of(tlsStrategy, mtlsStrategy)
        );
    }

    private MqttProperties propertiesWithStrategy(String strategy) {
        MqttProperties properties = new MqttProperties();
        properties.setNowMqttSecurityStrategy(strategy);
        return properties;
    }
}
