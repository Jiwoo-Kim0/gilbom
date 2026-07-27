package com.gilbom.robot.data.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RobotRegistrationRequest(
        @NotBlank(message = "로봇 이름은 필수입니다.")
        @Size(max = 100, message = "로봇 이름은 100자 이하여야 합니다.")
        @Pattern(
                regexp = "^(?!\\s)(?!.*\\s$)[^\\p{Cc}]+$",
                message = "로봇 이름의 앞뒤 공백과 제어 문자는 허용되지 않습니다.")
        String robotName,

        @NotBlank(message = "시리얼 번호는 필수입니다.")
        @Size(max = 100, message = "시리얼 번호는 100자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$",
                message = "시리얼 번호 형식이 올바르지 않습니다.")
        String serialNumber
) {
}
