package com.gilbom.auth.mfa.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TOTP secret key material은 코드나 DB가 아니라 외부 Secret 저장소에서 주입한다.
 *
 * keyring 형식: version=base64-encoded-32-byte-key;version=base64-encoded-32-byte-key
 */
@Component
@ConfigurationProperties(prefix = "gilbom.security.mfa.totp-secret-encryption")
public class TotpSecretEncryptionProperties {

    private boolean enabled;
    private boolean required;
    private int activeKeyVersion = 1;
    private String keyring = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public int getActiveKeyVersion() {
        return activeKeyVersion;
    }

    public void setActiveKeyVersion(int activeKeyVersion) {
        this.activeKeyVersion = activeKeyVersion;
    }

    public String getKeyring() {
        return keyring;
    }

    public void setKeyring(String keyring) {
        this.keyring = keyring == null ? "" : keyring;
    }
}
