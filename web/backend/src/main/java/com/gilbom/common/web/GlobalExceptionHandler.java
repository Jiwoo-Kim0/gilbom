package com.gilbom.common.web;

import java.io.IOException;
import java.sql.SQLException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.gilbom.common.api.ErrorResponse;
import com.gilbom.common.api.ValidationErrorDetail;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.audit.SecurityAuditService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_REJECTED_VALUE_LENGTH = 200;
    private static final Set<String> SENSITIVE_FIELD_TOKENS = Set.of(
            "password",
            "passwd",
            "passcode",
            "token",
            "secret",
            "apikey",
            "authorization",
            "credential",
            "cookie",
            "setcookie",
            "session",
            "csrf",
            "totp",
            "otp",
            "code",
            "recoverycode",
            "privatekey",
            "presignedurl");

    private final ObjectProvider<SecurityAuditService> securityAuditProvider;

    public GlobalExceptionHandler(
            ObjectProvider<SecurityAuditService> securityAuditProvider
    ) {
        this.securityAuditProvider = securityAuditProvider;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COMMON_VALIDATION_FAILED,
                ErrorCode.COMMON_VALIDATION_FAILED.defaultMessage(),
                bindingDetails(exception.getBindingResult()),
                request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        exception.getParameterValidationResults().forEach(result -> {
            String field = parameterName(result.getMethodParameter());
            result.getResolvableErrors().forEach(error -> details.add(new ValidationErrorDetail(
                    field,
                    constraintCode(error),
                    sanitizeRejectedValue(field, result.getArgument()),
                    safeReason(error.getDefaultMessage()))));
        });
        exception.getCrossParameterValidationResults().forEach(error -> details.add(
                new ValidationErrorDetail(
                        "$",
                        constraintCode(error),
                        null,
                        safeReason(error.getDefaultMessage()))));

        return respond(
                ErrorCode.COMMON_VALIDATION_FAILED,
                ErrorCode.COMMON_VALIDATION_FAILED.defaultMessage(),
                details,
                request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ValidationErrorDetail> details = exception.getConstraintViolations().stream()
                .map(this::constraintViolationDetail)
                .toList();

        return respond(
                ErrorCode.COMMON_VALIDATION_FAILED,
                ErrorCode.COMMON_VALIDATION_FAILED.defaultMessage(),
                details,
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        if (RequestBodyTooLargeException.isCausedBy(exception)) {
            return respond(ErrorCode.COMMON_PAYLOAD_TOO_LARGE, List.of(), request);
        }
        return respond(ErrorCode.COMMON_MALFORMED_JSON, List.of(), request);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COMMON_INVALID_REQUEST,
                List.of(invalidRequestDetail(exception)),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COMMON_RESOURCE_NOT_FOUND, List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COMMON_METHOD_NOT_ALLOWED, List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COMMON_UNSUPPORTED_MEDIA_TYPE, List.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.IMAGE_TOO_LARGE, List.of(), request);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.errorCode().defaultMessage();
        }
        return respond(exception.errorCode(), message, List.of(), request);
    }

    @ExceptionHandler(AuthenticationRateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationRateLimitExceeded(
            AuthenticationRateLimitExceededException exception,
            HttpServletRequest request
    ) {
        ErrorResponse body = ErrorResponse.of(
                exception.errorCode(),
                exception.getMessage(),
                request.getRequestURI(),
                requestId(request),
                List.of()
        );
        return ResponseEntity.status(exception.errorCode().httpStatus())
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.RETRY_AFTER,
                        Long.toString(exception.retryAfterSeconds())
                )
                .body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.AUTH_UNAUTHENTICATED, List.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException exception,
            HttpServletRequest request) {
        // 메서드 보안(@PreAuthorize) 거부는 MVC 안에서 발생하므로 필터용 handler와 별도로 처리한다.
        SecurityAuditService auditService =
                securityAuditProvider.getIfAvailable();
        if (auditService != null) {
            auditService.recordAccessDenied(
                    request,
                    SecurityContextHolder.getContext().getAuthentication()
            );
        }
        return respond(ErrorCode.AUTH_FORBIDDEN, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        String requestId = requestId(request);
        // 예외 객체·message·cause를 기록하면 SQL, bind 값, Secret이 함께 노출될 수 있다.
        // 운영 로그에는 추적 가능한 requestId와 제한된 안전 분류만 남긴다.
        log.error(
                "Unhandled request failure. requestId={} category={}",
                requestId,
                exceptionCategory(exception));
        return respond(
                ErrorCode.COMMON_INTERNAL_ERROR,
                ErrorCode.COMMON_INTERNAL_ERROR.defaultMessage(),
                List.of(),
                request,
                requestId);
    }

    private String exceptionCategory(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof DataAccessException || current instanceof SQLException) {
                return "DATABASE";
            }
            if (current instanceof IOException) {
                return "IO";
            }
            if (current instanceof SecurityException) {
                return "SECURITY";
            }
            current = current.getCause();
        }
        return "APPLICATION";
    }

    private List<ValidationErrorDetail> bindingDetails(BindingResult bindingResult) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            details.add(new ValidationErrorDetail(
                    fieldError.getField(),
                    constraintCode(fieldError),
                    sanitizeRejectedValue(fieldError.getField(), fieldError.getRejectedValue()),
                    safeReason(fieldError.getDefaultMessage())));
        }
        for (ObjectError objectError : bindingResult.getGlobalErrors()) {
            details.add(new ValidationErrorDetail(
                    "$",
                    constraintCode(objectError),
                    null,
                    safeReason(objectError.getDefaultMessage())));
        }
        return details;
    }

    private ValidationErrorDetail constraintViolationDetail(ConstraintViolation<?> violation) {
        String field = constraintField(violation.getPropertyPath().toString());
        String constraint = violation.getConstraintDescriptor()
                .getAnnotation()
                .annotationType()
                .getSimpleName();
        return new ValidationErrorDetail(
                field,
                constraint,
                sanitizeRejectedValue(field, violation.getInvalidValue()),
                safeReason(violation.getMessage()));
    }

    private ValidationErrorDetail invalidRequestDetail(Exception exception) {
        if (exception instanceof MissingServletRequestParameterException missingParameter) {
            return new ValidationErrorDetail(
                    missingParameter.getParameterName(),
                    "Required",
                    null,
                    "필수 요청 파라미터가 누락되었습니다.");
        }
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            return new ValidationErrorDetail(
                    missingHeader.getHeaderName(),
                    "Required",
                    null,
                    "필수 요청 헤더가 누락되었습니다.");
        }
        if (exception instanceof MethodArgumentTypeMismatchException typeMismatch) {
            return new ValidationErrorDetail(
                    typeMismatch.getName(),
                    "Type",
                    sanitizeRejectedValue(typeMismatch.getName(), typeMismatch.getValue()),
                    "요청 값의 타입이 올바르지 않습니다.");
        }
        return new ValidationErrorDetail("$", "Invalid", null, "요청 값이 올바르지 않습니다.");
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode,
            List<ValidationErrorDetail> details,
            HttpServletRequest request) {
        return respond(errorCode, errorCode.defaultMessage(), details, request);
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode,
            String message,
            List<ValidationErrorDetail> details,
            HttpServletRequest request) {
        return respond(errorCode, message, details, request, requestId(request));
    }

    private ResponseEntity<ErrorResponse> respond(
            ErrorCode errorCode,
            String message,
            List<ValidationErrorDetail> details,
            HttpServletRequest request,
            String requestId) {
        ErrorResponse body = ErrorResponse.of(
                errorCode,
                message,
                request.getRequestURI(),
                requestId,
                details);
        return ResponseEntity.status(errorCode.httpStatus()).body(body);
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdFilter.getOrCreateRequestId(request);
    }

    private String parameterName(MethodParameter methodParameter) {
        String parameterName = methodParameter.getParameterName();
        return parameterName != null ? parameterName : "arg" + methodParameter.getParameterIndex();
    }

    private String constraintField(String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) {
            return "$";
        }
        int firstDot = propertyPath.indexOf('.');
        return firstDot >= 0 && firstDot < propertyPath.length() - 1
                ? propertyPath.substring(firstDot + 1)
                : propertyPath;
    }

    private String constraintCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes != null && codes.length > 0 ? codes[codes.length - 1] : "Invalid";
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "올바르지 않은 값입니다." : reason;
    }

    private String sanitizeRejectedValue(String field, Object rejectedValue) {
        if (rejectedValue == null || isSensitiveField(field) || rejectedValue instanceof MultipartFile) {
            return null;
        }
        if (!isSafeScalar(rejectedValue)) {
            return null;
        }

        String value = String.valueOf(rejectedValue);
        if (value.length() <= MAX_REJECTED_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_REJECTED_VALUE_LENGTH - 1) + "…";
    }

    private boolean isSensitiveField(String field) {
        if (field == null) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_FIELD_TOKENS.stream().anyMatch(normalized::contains);
    }

    private boolean isSafeScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof TemporalAccessor;
    }
}
