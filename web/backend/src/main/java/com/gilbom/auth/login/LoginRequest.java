package com.gilbom.auth.login;

import com.gilbom.auth.account.validation.ValidEmailAddress;
import com.gilbom.auth.password.validation.ValidPassword;

public record LoginRequest(
        @ValidEmailAddress
        String email,

        @ValidPassword
        String password
) {
}
