package com.gilbom.common.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.gilbom.security.web.SecurityHeadersFilter;

@WebMvcTest(
        controllers = GlobalExceptionTestController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import({
        GlobalExceptionHandler.class,
        RequestIdFilter.class,
        RequestBodySizeLimitFilter.class,
        SecurityHeadersFilter.class
})
class GlobalExceptionHandlerTest {

    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successfulResponsesReturnTheDtoWithoutACommonEnvelope() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"정기 점검"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("정기 점검")))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    void beanValidationErrorsUseTheCommonErrorSchema() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.code", is("COMMON_VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", is("요청 값 검증에 실패했습니다.")))
                .andExpect(jsonPath("$.path", is("/test/validation")))
                .andExpect(jsonPath("$.requestId", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].field", is("title")))
                .andExpect(jsonPath("$.details[0].constraint", is("NotBlank")))
                .andExpect(jsonPath("$.details[0].rejectedValue", is("")))
                .andExpect(jsonPath("$.details[0].reason", is("제목은 필수입니다.")));
    }

    @Test
    void sensitiveRejectedValuesAreNeverReflected() throws Exception {
        mockMvc.perform(post("/test/secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field", is("password")))
                .andExpect(jsonPath("$.details[0].rejectedValue", nullValue()));
    }

    @Test
    void malformedJsonHasAStableErrorCodeAndNoInternalDetails() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_MALFORMED_JSON")))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void oversizedRequestBodiesUseTheCommon413Contract() throws Exception {
        byte[] oversizedBody = new byte[(1024 * 1024) + 1];

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
                .andExpect(jsonPath("$.status", is(413)))
                .andExpect(jsonPath("$.code", is("COMMON_PAYLOAD_TOO_LARGE")))
                .andExpect(jsonPath("$.message", is("요청 본문이 허용된 상한을 초과했습니다.")))
                .andExpect(jsonPath("$.path", is("/test/validation")))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void methodParameterValidationIncludesFieldAndRejectedValue() throws Exception {
        mockMvc.perform(get("/test/method-validation").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_VALIDATION_FAILED")))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].field", is("count")))
                .andExpect(jsonPath("$.details[0].rejectedValue", is("0")));
    }

    @Test
    void typeMismatchUsesInvalidRequest() throws Exception {
        mockMvc.perform(get("/test/type").param("count", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("COMMON_INVALID_REQUEST")))
                .andExpect(jsonPath("$.details[0].field", is("count")))
                .andExpect(jsonPath("$.details[0].constraint", is("Type")))
                .andExpect(jsonPath("$.details[0].rejectedValue", is("not-a-number")));
    }

    @Test
    void businessExceptionsUseTheirDeclaredStatusAndCode() throws Exception {
        String requestId = "40cfeada-a03a-4fd6-b158-5140d8dba09a";

        mockMvc.perform(get("/test/conflict")
                        .header(RequestIdFilter.HEADER_NAME, requestId))
                .andExpect(status().isConflict())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, requestId))
                .andExpect(jsonPath("$.code", is("COMMAND_STATE_CONFLICT")))
                .andExpect(jsonPath("$.requestId", is(requestId)))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void semanticBusinessRuleViolationsUse422() throws Exception {
        mockMvc.perform(get("/test/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("REPORT_VALIDATION_FAILED")));
    }

    @Test
    void reportStateConflictsUseTheDedicated409Code() throws Exception {
        mockMvc.perform(get("/test/error").param("code", "REPORT_STATE_CONFLICT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REPORT_STATE_CONFLICT")));
    }

    @Test
    void dependencyFailuresUse503() throws Exception {
        mockMvc.perform(get("/test/dependency"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("MQTT_UNAVAILABLE")));
    }

    @Test
    void nonX509MqttCertificatesUseTheDedicatedErrorCode() throws Exception {
        mockMvc.perform(get("/test/mqtt-certificate"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("MQTT_CERTIFICATE_NOT_X509")))
                .andExpect(jsonPath("$.message", is("`TEST` 인증서는 X.509 형식이 아닙니다.")))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void mqttContractBusinessExceptionsUseTheirDeclaredStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/error")
                        .param("code", "MQTT_PAYLOAD_VALIDATION_FAILED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("MQTT_PAYLOAD_VALIDATION_FAILED")))
                .andExpect(jsonPath(
                        "$.message",
                        is("MQTT Payload가 통신 계약을 충족하지 않습니다.")
                ))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void unsupportedMethodsUse405() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code", is("COMMON_METHOD_NOT_ALLOWED")));
    }

    @Test
    void unsupportedContentTypesUse415() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("title"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code", is("COMMON_UNSUPPORTED_MEDIA_TYPE")));
    }

    @Test
    void oversizedUploadsUse413() throws Exception {
        mockMvc.perform(get("/test/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("IMAGE_TOO_LARGE")));
    }

    @Test
    void unexpectedExceptionsDoNotExposeInternalMessages() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("COMMON_INTERNAL_ERROR")))
                .andExpect(jsonPath("$.message", is("서버 내부 오류가 발생했습니다.")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database-password"))))
                .andExpect(jsonPath("$.details", hasSize(0)));
    }

    @Test
    void authenticationAndAuthorizationFailuresUse401And403() throws Exception {
        mockMvc.perform(get("/test/error").param("code", "AUTH_UNAUTHENTICATED"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("AUTH_UNAUTHENTICATED")));

        mockMvc.perform(get("/test/error").param("code", "AUTH_FORBIDDEN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUTH_FORBIDDEN")));
    }

    @Test
    void reportAuthorizationFailuresUseTheReportSpecific403Code() throws Exception {
        mockMvc.perform(get("/api/v1/reports/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("REPORT_ACCESS_DENIED")));
    }

    @Test
    void domainNotFoundAndRateLimitFailuresUse404And429() throws Exception {
        mockMvc.perform(get("/test/error").param("code", "ROBOT_NOT_FOUND"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ROBOT_NOT_FOUND")));

        mockMvc.perform(get("/test/error").param("code", "COMMON_RATE_LIMIT_EXCEEDED"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("COMMON_RATE_LIMIT_EXCEEDED")));
    }

    @Test
    void invalidExternalRequestIdsAreReplacedInsteadOfReflected() throws Exception {
        mockMvc.perform(get("/test/conflict")
                        .header(RequestIdFilter.HEADER_NAME, "not-a-valid-request-id"))
                .andExpect(status().isConflict())
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.requestId", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.requestId").value(
                        org.hamcrest.Matchers.not(is("not-a-valid-request-id"))));
    }

}
