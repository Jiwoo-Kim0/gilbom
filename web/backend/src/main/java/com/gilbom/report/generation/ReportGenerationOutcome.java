package com.gilbom.report.generation;

import java.util.Objects;

import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportGenerationFallbackReason;
import com.gilbom.report.ReportGenerationMethod;

public record ReportGenerationOutcome(
        ReportContent content,
        ReportGenerationFallbackReason fallbackReason,
        int externalAttempts
) {

    public ReportGenerationOutcome {
        Objects.requireNonNull(content, "content must not be null");
        if (externalAttempts < 0 || externalAttempts > 2) {
            throw new IllegalArgumentException(
                    "externalAttempts must be between zero and two");
        }
        if (content.generationMethod()
                == ReportGenerationMethod.LLM
                && fallbackReason != null) {
            throw new IllegalArgumentException(
                    "LLM result cannot have a fallback reason");
        }
        if (content.generationMethod()
                == ReportGenerationMethod.TEMPLATE
                && fallbackReason == null) {
            throw new IllegalArgumentException(
                    "Template fallback requires a safe reason");
        }
        if (fallbackReason != null
                && content.generationFallbackReason()
                        != fallbackReason) {
            throw new IllegalArgumentException(
                    "Fallback reason must be persisted in content");
        }
    }

    public boolean fallbackUsed() {
        return fallbackReason != null;
    }
}
