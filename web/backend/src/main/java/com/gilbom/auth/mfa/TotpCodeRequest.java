package com.gilbom.auth.mfa;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TotpCodeRequest(
        @NotNull(message = "TOTP 코드는 필수입니다.")
        @Pattern(
                regexp = "^[0-9]{6}$",
                message = "TOTP 코드는 숫자 6자리여야 합니다."
        )
        String code
) {
}
