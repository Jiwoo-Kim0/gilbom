package com.gilbom.robot.mqtt.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Arrays;


/*not lombok*/
@Component
@Validated
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {

    @NotBlank
    private String nowMqttSecurityStrategy;
    @NotBlank
    private String broker;
    @NotBlank
    private String clientId;
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotBlank
    private String subscribeTopic;
    @NotBlank
    private String publishTopic;
    @Min(0)
    @Max(2)
    private int qos;
    private boolean logPayload;
    private Duration reconnectMaxDelay = Duration.ofSeconds(5);

    /*For mqtt 보안 전략*/
    // @ConfigurationProperties == mqtt.now-mqtt-security-strategy

    private Security security = new Security();

    public String getNowMqttSecurityStrategy() {
        return nowMqttSecurityStrategy;
    }

    public void setNowMqttSecurityStrategy(String nowMqttSecurityStrategy) {
        this.nowMqttSecurityStrategy = nowMqttSecurityStrategy;
    }

    public String getBroker() {
        return broker;
    }

    public void setBroker(String broker) {
        this.broker = broker;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSubscribeTopic() {
        return subscribeTopic;
    }

    public void setSubscribeTopic(String subscribeTopic) {
        this.subscribeTopic = subscribeTopic;
    }

    public String[] getSubscribeTopics() {
        return Arrays.stream(subscribeTopic.split(","))
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .toArray(String[]::new);
    }

    public String getPublishTopic() {
        return publishTopic;
    }

    public void setPublishTopic(String publishTopic) {
        this.publishTopic = publishTopic;
    }

    public int getQos() {
        return qos;
    }

    public void setQos(int qos) {
        this.qos = qos;
    }

    public boolean isLogPayload() {
        return logPayload;
    }

    public void setLogPayload(boolean logPayload) {
        this.logPayload = logPayload;
    }

    public Duration getReconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public void setReconnectMaxDelay(Duration reconnectMaxDelay) {
        if (reconnectMaxDelay == null
                || reconnectMaxDelay.compareTo(Duration.ofSeconds(1)) < 0
                || reconnectMaxDelay.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "mqtt.reconnect-max-delay must be between 1 second "
                            + "and " + Integer.MAX_VALUE + " milliseconds");
        }
        this.reconnectMaxDelay = reconnectMaxDelay;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public static class Security {
    	/*For 인증서 파일 경로*/
    	// mqtt.security. trust-certificate
        private String trustCertificate = ""; //
        private String clientCertificate = ""; //mtsl 필요
        private String clientPrivateKey = ""; //mtsl 필요

        public String getTrustCertificate() {
            return trustCertificate;
        }

        public void setTrustCertificate(String trustCertificate) {
            this.trustCertificate = trustCertificate;
        }

        public String getClientCertificate() {
            return clientCertificate;
        }

        public void setClientCertificate(String clientCertificate) {
            this.clientCertificate = clientCertificate;
        }

        public String getClientPrivateKey() {
            return clientPrivateKey;
        }

        public void setClientPrivateKey(String clientPrivateKey) {
            this.clientPrivateKey = clientPrivateKey;
        }
    }
}
