package com.gilbom.robot.credential;

public interface MqttCredentialProvisioner {

    ProvisionedMqttCredential provision(String username, String password);

    ProvisionedMqttCredential rotate(
            String username,
            String expectedPasswordHash,
            String password
    );

    void revoke(String username, String expectedPasswordHash);

    void restore(String username, String passwordHash);

    void remove(String username);

    CredentialRegistrySnapshot inspect(String username);

    record ProvisionedMqttCredential(String passwordHash) {

        public ProvisionedMqttCredential {
            if (passwordHash == null || passwordHash.isBlank()) {
                throw new IllegalArgumentException("passwordHash must not be blank");
            }
        }
    }

    record CredentialRegistrySnapshot(String passwordHash) {

        public boolean present() {
            return passwordHash != null;
        }
    }
}
