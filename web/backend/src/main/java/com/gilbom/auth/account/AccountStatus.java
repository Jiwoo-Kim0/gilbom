package com.gilbom.auth.account;

/**
 * 계정 생명주기 상태다. 임시 잠금은 account_status를 덮어쓰지 않고 locked_until로 판단한다.
 */
public enum AccountStatus {
    PENDING_APPROVAL(false),
    ACTIVE(true),
    DISABLED(false),
    DELETED(false);

    private final boolean authenticationEligible;

    AccountStatus(boolean authenticationEligible) {
        this.authenticationEligible = authenticationEligible;
    }

    public boolean isAuthenticationEligible() {
        return authenticationEligible;
    }
}
