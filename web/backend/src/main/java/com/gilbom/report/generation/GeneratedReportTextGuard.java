package com.gilbom.report.generation;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.llm.LlmOutboundInputGuard;

/**
 * 생성 텍스트는 데이터로만 취급하고 실행 가능한 HTML·script·command를 거부한다.
 */
@Component
public class GeneratedReportTextGuard {

    private static final Pattern HTML_OR_SCRIPT = Pattern.compile(
            "(?i)(?:<\\s*/?\\s*(?:script|iframe|object|embed|style|svg"
                    + "|img|a|form|input|meta|link)\\b"
                    + "|javascript\\s*:|on(?:load|error|click)\\s*=)");
    private static final Pattern EXECUTABLE_COMMAND = Pattern.compile(
            "(?i)(?:\\brm\\s+-rf\\b|\\bcurl\\s+https?://"
                    + "|\\bwget\\s+https?://|\\bpowershell(?:\\.exe)?\\b"
                    + "|\\bcmd\\.exe\\b|\\bbash\\s+-c\\b"
                    + "|\\bsh\\s+-c\\b|\\bsudo\\s+\\S+"
                    + "|\\b(?:drop|truncate)\\s+(?:table|schema|database)\\b)");

    private final LlmOutboundInputGuard outboundGuard;

    public GeneratedReportTextGuard(ObjectMapper objectMapper) {
        outboundGuard = new LlmOutboundInputGuard(objectMapper);
    }

    public void validate(String value) {
        if (value == null
                || HTML_OR_SCRIPT.matcher(value).find()
                || EXECUTABLE_COMMAND.matcher(value).find()) {
            throw invalid();
        }
        try {
            outboundGuard.validate(Map.of("text", value));
        } catch (BusinessException exception) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.LLM_RESPONSE_INVALID);
    }
}
