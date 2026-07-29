package com.gilbom.robot.credential;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt.credential-provisioning")
public class MqttCredentialProvisioningProperties {

    private boolean enabled;
    private String passwordFile = "";
    private String passwordCommand = "mosquitto_passwd";
    private String publicBrokerUri = "";
    private Duration processTimeout = Duration.ofSeconds(10);
    private Duration activationTimeout = Duration.ofSeconds(10);
    private Duration verificationInterval = Duration.ofMillis(250);
    private Duration registryScanInterval = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPasswordFile() {
        return passwordFile;
    }

    public void setPasswordFile(String passwordFile) {
        this.passwordFile = passwordFile;
    }

    public String getPasswordCommand() {
        return passwordCommand;
    }

    public void setPasswordCommand(String passwordCommand) {
        this.passwordCommand = passwordCommand;
    }

    public String getPublicBrokerUri() {
        return publicBrokerUri;
    }

    public void setPublicBrokerUri(String publicBrokerUri) {
        this.publicBrokerUri = publicBrokerUri;
    }

    public Duration getProcessTimeout() {
        return processTimeout;
    }

    public void setProcessTimeout(Duration processTimeout) {
        this.processTimeout = processTimeout;
    }

    public Duration getActivationTimeout() {
        return activationTimeout;
    }

    public void setActivationTimeout(Duration activationTimeout) {
        this.activationTimeout = activationTimeout;
    }

    public Duration getVerificationInterval() {
        return verificationInterval;
    }

    public void setVerificationInterval(Duration verificationInterval) {
        this.verificationInterval = verificationInterval;
    }

    public Duration getRegistryScanInterval() {
        return registryScanInterval;
    }

    public void setRegistryScanInterval(Duration registryScanInterval) {
        this.registryScanInterval = registryScanInterval;
    }
}
