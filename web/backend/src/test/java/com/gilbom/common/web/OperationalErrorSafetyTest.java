package com.gilbom.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("ec2")
@WebMvcTest(
        controllers = GlobalExceptionTestController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class OperationalErrorSafetyTest {

    private static final String REQUEST_ID = "3951cab7-21be-44dd-8db0-0e8ce2ed9756";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void sqlExceptionsExposeOnlyTheCommonErrorAndSafeLogCategory(CapturedOutput output)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/test/sql-error")
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID))
                .andExpect(jsonPath("$.status", is(500)))
                .andExpect(jsonPath("$.code", is("COMMON_INTERNAL_ERROR")))
                .andExpect(jsonPath("$.message", is("서버 내부 오류가 발생했습니다.")))
                .andExpect(jsonPath("$.requestId", is(REQUEST_ID)))
                .andExpect(jsonPath("$.details", hasSize(0)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("auth.admin_user")
                .doesNotContain("password_hash")
                .doesNotContain("must-not-leak")
                .doesNotContain("SQLException");

        assertThat(output.getAll())
                .contains("requestId=" + REQUEST_ID)
                .contains("category=DATABASE")
                .doesNotContain("auth.admin_user")
                .doesNotContain("password_hash")
                .doesNotContain("must-not-leak")
                .doesNotContain("SQLException")
                .doesNotContain("GlobalExceptionTestController.sqlError");
    }

    @Test
    void operationalProfileDisablesFallbackDetailsStackTracesAndSqlLoggers() {
        assertThat(environment.getProperty("server.error.include-exception"))
                .isEqualTo("false");
        assertThat(environment.getProperty("server.error.include-message"))
                .isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-stacktrace"))
                .isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-binding-errors"))
                .isEqualTo("never");
        assertThat(environment.getProperty("logging.exception-conversion-word"))
                .isEqualTo("%nopex");
        assertThat(environment.getProperty("spring.jpa.show-sql"))
                .isEqualTo("false");
        assertThat(environment.getProperty("server.servlet.session.cookie.name"))
                .isEqualTo("__Host-GILBOM_SESSION");
        assertThat(environment.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("true");
        assertThat(environment.getProperty("logging.level.org.hibernate.SQL"))
                .isEqualTo("OFF");
        assertThat(environment.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
                .isEqualTo("OFF");
        assertThat(environment.getProperty(
                "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper"))
                .isEqualTo("OFF");
        assertThat(environment.getProperty("logging.level.org.springframework.jdbc.core"))
                .isEqualTo("OFF");
    }
}
