package com.gilbom.auth.registration;

import com.gilbom.auth.account.AccountStatus;

public record PublicRegistrationResponse(
        AccountStatus accountStatus,
        String nextAction
) {

    public static final String LOGIN_NEXT_ACTION = "LOGIN";

    public static PublicRegistrationResponse pendingApproval() {
        return new PublicRegistrationResponse(
                AccountStatus.PENDING_APPROVAL,
                LOGIN_NEXT_ACTION
        );
    }
}
