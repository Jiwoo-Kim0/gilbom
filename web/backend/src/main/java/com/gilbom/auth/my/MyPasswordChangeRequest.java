package com.gilbom.auth.my;

import com.gilbom.auth.password.validation.ValidPassword;

public record MyPasswordChangeRequest(
        @ValidPassword
        String currentPassword,
        @ValidPassword
        String newPassword
) {
}
