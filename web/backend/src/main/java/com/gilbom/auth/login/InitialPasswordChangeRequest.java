package com.gilbom.auth.login;

import com.gilbom.auth.password.validation.ValidPassword;

public record InitialPasswordChangeRequest(
        @ValidPassword
        String newPassword
) {
}
