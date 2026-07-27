package com.gilbom.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 초기 관리자 값은 코드나 기본값이 아니라 실행 환경의 Secret에서만 주입한다.
 */
@Component
@ConfigurationProperties(prefix = "gilbom.security.initial-admin")
public class InitialAdminBootstrapProperties {

    private boolean enabled;
    private String email = "";
    private String password = "";
    private String displayName = "";
    private String userCode = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = valueOrEmpty(email);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = valueOrEmpty(password);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = valueOrEmpty(displayName);
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = valueOrEmpty(userCode);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
