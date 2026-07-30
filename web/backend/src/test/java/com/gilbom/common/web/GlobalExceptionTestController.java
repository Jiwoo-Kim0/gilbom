package com.gilbom.common.web;

import java.sql.SQLException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@RestController
public class GlobalExceptionTestController {

    @PostMapping(path = "/test/validation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ValidationRequest validate(@Valid @RequestBody ValidationRequest request) {
        return request;
    }

    @PostMapping(path = "/test/secret", consumes = MediaType.APPLICATION_JSON_VALUE)
    SecretRequest secret(@Valid @RequestBody SecretRequest request) {
        return request;
    }

    @PostMapping(
            path = "/test/image-secret",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ImageSecretRequest imageSecret(
            @Valid @RequestBody ImageSecretRequest request
    ) {
        return request;
    }

    @GetMapping("/test/method-validation")
    int methodValidation(@RequestParam @Min(value = 1, message = "1 이상이어야 합니다.") int count) {
        return count;
    }

    @GetMapping("/test/type")
    int type(@RequestParam int count) {
        return count;
    }

    @GetMapping("/test/conflict")
    void conflict() {
        throw new BusinessException(ErrorCode.COMMAND_STATE_CONFLICT);
    }

    @GetMapping("/test/business-rule")
    void businessRule() {
        throw new BusinessException(ErrorCode.REPORT_VALIDATION_FAILED);
    }

    @GetMapping("/test/dependency")
    void dependency() {
        throw new BusinessException(ErrorCode.MQTT_UNAVAILABLE);
    }

    @GetMapping("/test/mqtt-certificate")
    void mqttCertificate() {
        throw new BusinessException(
                ErrorCode.MQTT_CERTIFICATE_NOT_X509,
                "`TEST` 인증서는 X.509 형식이 아닙니다."
        );
    }

    @GetMapping("/test/too-large")
    void tooLarge() {
        throw new MaxUploadSizeExceededException(500_000);
    }

    @GetMapping("/test/unexpected")
    void unexpected() {
        throw new IllegalStateException(
                "database-password=must-not-leak; "
                        + "Authorization=Bearer secret-access-token; "
                        + "totpSecret=JBSWY3DPEHPK3PXP"
        );
    }

    @GetMapping("/test/sql-error")
    void sqlError() {
        SQLException cause = new SQLException(
                "relation auth.admin_user does not exist; "
                        + "bind password=must-not-leak; "
                        + "Authorization=Bearer secret-access-token; "
                        + "totpSecret=JBSWY3DPEHPK3PXP"
        );
        throw new DataAccessResourceFailureException(
                "SQL [select password_hash from auth.admin_user]",
                cause
        );
    }

    @GetMapping("/test/error")
    void error(@RequestParam ErrorCode code) {
        throw new BusinessException(code);
    }

    @GetMapping("/api/v1/reports/test/access-denied")
    void reportAccessDenied() {
        throw new AccessDeniedException("report permission denied");
    }

    public record ValidationRequest(
            @NotBlank(message = "제목은 필수입니다.")
            String title) {
    }

    public record SecretRequest(
            @NotBlank(message = "비밀번호는 필수입니다.")
            String password) {
    }

    public record ImageSecretRequest(
            @NotBlank(message = "이미지는 필수입니다.")
            String imageBase64) {
    }
}
