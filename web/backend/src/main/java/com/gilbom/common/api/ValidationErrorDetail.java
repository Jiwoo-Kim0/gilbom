package com.gilbom.common.api;

public record ValidationErrorDetail(
        String field,
        String constraint,
        String rejectedValue,
        String reason) {
}
