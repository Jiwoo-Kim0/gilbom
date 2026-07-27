package com.gilbom.auth.mfa;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RecoveryCodeRequest(
        @NotNull(message = "복구 코드는 필수입니다.")
        @Pattern(
                regexp = RecoveryCodeService.CODE_PATTERN,
                message = "복구 코드 형식이 올바르지 않습니다."
        )
        String recoveryCode
) {
}
