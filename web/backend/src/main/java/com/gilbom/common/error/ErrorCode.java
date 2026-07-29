package com.gilbom.common.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트가 HTTP 상태와 별개로 안정적으로 분기할 수 있는 공개 오류 코드다.
 */
public enum ErrorCode {

    COMMON_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    COMMON_MALFORMED_JSON(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다."),
    COMMON_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값 검증에 실패했습니다."),
    MQTT_TOPIC_INVALID(HttpStatus.BAD_REQUEST, "MQTT Topic 형식이 올바르지 않습니다."),
    MQTT_TOPIC_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 MQTT Topic입니다."),
    MQTT_PAYLOAD_MALFORMED(HttpStatus.BAD_REQUEST, "MQTT Payload를 해석할 수 없습니다."),

    AUTH_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 수행할 권한이 없습니다."),
    AUTH_PASSWORD_REUSE_NOT_ALLOWED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "새 비밀번호는 현재 비밀번호와 달라야 합니다."
    ),
    AUTH_MFA_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MFA 서비스를 일시적으로 사용할 수 없습니다."
    ),
    AUTH_ADMIN_REAUTHENTICATION_FAILED(
            HttpStatus.FORBIDDEN,
            "관리자 재인증에 실패했습니다."
    ),
    AUTH_LAST_ADMIN_PROTECTED(
            HttpStatus.CONFLICT,
            "마지막 활성 관리자는 비활성화하거나 관리 권한을 제거할 수 없습니다."
    ),

    COMMON_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    AUTH_USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    AUTH_ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "역할을 찾을 수 없습니다."),
    AUTH_PERMISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "권한을 찾을 수 없습니다."),
    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "로봇을 찾을 수 없습니다."),
    PATROL_AREA_NOT_FOUND(HttpStatus.NOT_FOUND, "순찰 구역을 찾을 수 없습니다."),
    PATROL_ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "순찰 경로를 찾을 수 없습니다."),
    MISSION_NOT_FOUND(HttpStatus.NOT_FOUND, "임무를 찾을 수 없습니다."),
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "결함을 찾을 수 없습니다."),
    COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "명령을 찾을 수 없습니다."),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다."),
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),

    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    COMMON_CONFLICT(HttpStatus.CONFLICT, "요청이 현재 리소스 상태와 충돌합니다."),

    ROBOT_SERIAL_NUMBER_CONFLICT(HttpStatus.CONFLICT, "이미 등록된 로봇 시리얼 번호입니다."),
    PATROL_AREA_CODE_CONFLICT(HttpStatus.CONFLICT, "이미 등록된 순찰 구역 코드입니다."),
    PATROL_AREA_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 순찰 구역 상태에서는 요청을 수행할 수 없습니다."),
    PATROL_AREA_IN_USE(HttpStatus.CONFLICT, "진행 중이거나 예정된 임무가 사용하는 순찰 구역입니다."),
    PATROL_ROUTE_VERSION_CONFLICT(HttpStatus.CONFLICT, "순찰 경로 버전 생성이 충돌했습니다."),
    ROBOT_CREDENTIAL_CONFLICT(
            HttpStatus.CONFLICT,
            "이미 활성화된 로봇 MQTT 인증정보가 있습니다."
    ),
    ROBOT_CREDENTIAL_SECRET_ALREADY_ISSUED(
            HttpStatus.CONFLICT,
            "이미 처리된 인증정보 발급 요청입니다. 비밀번호는 다시 표시할 수 없습니다."
    ),

    AUTH_EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    COMMAND_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 명령 상태에서는 요청을 수행할 수 없습니다."),
    MISSION_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 임무 상태에서는 요청을 수행할 수 없습니다."),
    MQTT_EVENT_CONFLICT(HttpStatus.CONFLICT, "MQTT Event 식별자 또는 순번이 기존 데이터와 충돌합니다."),
    REPORT_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 보고서 상태에서는 요청을 수행할 수 없습니다."),
    DEFECT_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 결함 상태에서는 요청을 수행할 수 없습니다."),
    DEFECT_MERGE_NOT_ALLOWED(HttpStatus.CONFLICT, "요청한 결함은 병합할 수 없습니다."),

    COMMON_PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청 본문이 허용된 상한을 초과했습니다."),
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 크기가 허용된 상한을 초과했습니다."),
    COMMON_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    IMAGE_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 이미지 형식입니다."),

    COMMON_BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "업무 규칙을 충족하지 않습니다."),
    PATROL_AREA_GEOMETRY_INVALID(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "순찰 구역 경계는 유효한 Polygon 또는 MultiPolygon이어야 합니다."
    ),
    PATROL_AREA_ROUTE_OUTSIDE_BOUNDARY(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "변경한 순찰 구역 경계가 활성 또는 초안 경로를 포함하지 않습니다."
    ),
    PATROL_ROUTE_GEOMETRY_INVALID(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "순찰 경로는 유효한 LineString 또는 MultiLineString이어야 합니다."
    ),
    PATROL_ROUTE_OUTSIDE_AREA(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "순찰 경로는 선택한 순찰 구역 경계 안에 있어야 합니다."
    ),
    REPORT_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "보고서 조회 기간이 올바르지 않습니다."),
    STATISTICS_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "통계 조회 기간이 올바르지 않습니다."),
    MQTT_SCHEMA_VERSION_UNSUPPORTED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "지원하지 않는 MQTT Payload 버전입니다."
    ),
    MQTT_PAYLOAD_VALIDATION_FAILED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MQTT Payload가 통신 계약을 충족하지 않습니다."
    ),
    MQTT_ID_MISMATCH(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MQTT Topic과 Payload의 mqtt_id가 일치하지 않습니다."
    ),
    MQTT_QOS_MISMATCH(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MQTT QoS가 Topic 계약과 일치하지 않습니다."
    ),
    MQTT_MESSAGE_EXPIRED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MQTT 메시지의 유효시간이 만료되었습니다."
    ),
    MQTT_TIMESTAMP_IN_FUTURE(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "MQTT 메시지의 기준시각이 허용된 미래 오차를 초과했습니다."
    ),

    COMMON_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다."),

    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    COMMON_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    DB_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "데이터베이스를 일시적으로 사용할 수 없습니다."),
    S3_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "파일 저장소를 일시적으로 사용할 수 없습니다."),
    MQTT_CERTIFICATE_NOT_X509(HttpStatus.INTERNAL_SERVER_ERROR, "MQTT 인증서가 X.509 형식이 아닙니다."),
    MQTT_SECURITY_CONFIGURATION_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "MQTT 보안 설정이 올바르지 않습니다."),
    MQTT_CREDENTIAL_PROVISIONING_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MQTT 인증정보 발급 서비스를 일시적으로 사용할 수 없습니다."
    ),
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
