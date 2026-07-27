package com.gilbom.robot.credential;

public interface MqttCredentialProvisioner {

    ProvisionedMqttCredential provision(String username, String password);

    void remove(String username);

    record ProvisionedMqttCredential(String passwordHash) {

        public ProvisionedMqttCredential {
            if (passwordHash == null || passwordHash.isBlank()) {
                throw new IllegalArgumentException("passwordHash must not be blank");
            }
        }
    }
}
