package com.gilbom.auth.registration;

import com.gilbom.auth.account.validation.ValidEmailAddress;
import com.gilbom.auth.password.validation.ValidPassword;
import com.gilbom.auth.registration.validation.ValidDisplayName;

public record PublicRegistrationRequest(
        @ValidEmailAddress
        String email,

        @ValidPassword
        String password,

        @ValidDisplayName
        String displayName
) {
}
