package com.gilbom.common.api;

/**
 * Bean Validation과 요청 파라미터 오류의 필드별 공개 정보다.
 *
 * 비밀번호·Token·Secret 등 민감 필드의 rejectedValue는 반드시 null이어야 한다.
 */
public record ValidationErrorDetail(
        String field,
        String constraint,
        String rejectedValue,
        String reason) {
}
