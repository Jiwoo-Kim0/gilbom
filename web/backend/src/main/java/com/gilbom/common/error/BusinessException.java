package com.gilbom.common.error;

import java.util.Objects;

/**
 * 도메인 계층에서 공통 오류 코드와 외부 공개 가능한 메시지만 전달하는 예외다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String safeMessage) {
        // safeMessage는 API에 노출되므로 SQL, 경로, 개인정보, credential을 넣지 않는다.
        super(safeMessage);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, errorCode.defaultMessage(), cause);
    }

    public BusinessException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
