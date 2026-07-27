package com.gilbom.auth.registration;

import com.gilbom.auth.account.validation.ValidEmailAddress;

public record EmailAvailabilityRequest(
        @ValidEmailAddress
        String email
) {
}
