package com.gilbom.robot.credential;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.mqtt.config.MqttProperties;
import com.gilbom.robot.mqtt.factory.MqttSecurityStrategyFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MqttCredentialProvisioningProperties.class)
public class MqttCredentialProvisioningConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "mqtt.credential-provisioning",
            name = "enabled",
            havingValue = "true")
    MqttCredentialProvisioner passwordFileMqttCredentialProvisioner(
            MqttCredentialProvisioningProperties provisioningProperties,
            MqttProperties mqttProperties,
            MqttSecurityStrategyFactory securityStrategyFactory
    ) {
        return new PasswordFileMqttCredentialProvisioner(
                provisioningProperties,
                mqttProperties,
                securityStrategyFactory);
    }

    @Bean
    @ConditionalOnMissingBean(MqttCredentialProvisioner.class)
    MqttCredentialProvisioner unavailableMqttCredentialProvisioner() {
        return new MqttCredentialProvisioner() {
            @Override
            public ProvisionedMqttCredential provision(
                    String username,
                    String password
            ) {
                throw unavailable();
            }

            @Override
            public ProvisionedMqttCredential rotate(
                    String username,
                    String expectedPasswordHash,
                    String password
            ) {
                throw unavailable();
            }

            @Override
            public void revoke(
                    String username,
                    String expectedPasswordHash
            ) {
                throw unavailable();
            }

            @Override
            public void restore(String username, String passwordHash) {
                throw unavailable();
            }

            @Override
            public void remove(String username) {
                // Nothing was provisioned when the environment is disabled.
            }

            @Override
            public CredentialRegistrySnapshot inspect(String username) {
                throw unavailable();
            }

            private BusinessException unavailable() {
                return new BusinessException(
                        ErrorCode.MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE);
            }
        };
    }
}
