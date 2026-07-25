package com.gilbom.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    COMMON_MALFORMED_JSON(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다."),
    COMMON_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값 검증에 실패했습니다."),

    AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 수행할 권한이 없습니다."),

    COMMON_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "로봇을 찾을 수 없습니다."),
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "결함을 찾을 수 없습니다."),
    COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "명령을 찾을 수 없습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다."),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),

    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    COMMON_CONFLICT(HttpStatus.CONFLICT, "요청이 현재 리소스 상태와 충돌합니다."),
    COMMAND_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 명령 상태에서는 요청을 수행할 수 없습니다."),

    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기가 허용된 상한을 초과했습니다."),
    COMMON_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    IMAGE_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다."),

    COMMON_BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "업무 규칙을 충족하지 않습니다."),
    REPORT_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "보고서 조회 기간이 올바르지 않습니다."),

    COMMON_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다."),

    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    COMMON_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    DB_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "데이터베이스를 일시적으로 사용할 수 없습니다."),
    S3_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "파일 저장소를 일시적으로 사용할 수 없습니다."),
    MQTT_CERTIFICATE_NOT_X509(HttpStatus.INTERNAL_SERVER_ERROR, "MQTT 인증서가 X.509 형식이 아닙니다."),
    MQTT_SECURITY_CONFIGURATION_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "MQTT 보안 설정이 올바르지 않습니다."),
    MQTT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MQTT 서비스를 일시적으로 사용할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
