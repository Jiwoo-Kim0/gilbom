package com.gilbom.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * 승인 문서의 모델별 GMS credit 단가를 서버 상수로 고정한다.
 */
@Component
public class LlmCreditPolicy {

    public static final int MAXIMUM_INPUT_TOKENS = 8_000;

    public BigDecimal maximumAttemptCredits(
            String model,
            int maximumOutputTokens
    ) {
        return credits(
                model,
                MAXIMUM_INPUT_TOKENS,
                maximumOutputTokens);
    }

    public BigDecimal actualCredits(
            String model,
            LlmUsage usage
    ) {
        return credits(
                model,
                usage.inputTokens(),
                usage.outputTokens());
    }

    private static BigDecimal credits(
            String model,
            int inputTokens,
            int outputTokens
    ) {
        ModelPrice price = switch (model) {
            case "gpt-5.4-mini" -> new ModelPrice(
                    new BigDecimal("0.015"),
                    new BigDecimal("0.0675"));
            case "gpt-5.4" -> new ModelPrice(
                    new BigDecimal("0.05"),
                    new BigDecimal("0.225"));
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM model");
        };
        if (inputTokens < 0
                || outputTokens < 0) {
            throw new IllegalArgumentException(
                    "Token usage is outside the approved range");
        }
        return price.inputPerToken()
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(price.outputPerToken().multiply(
                        BigDecimal.valueOf(outputTokens)))
                .setScale(6, RoundingMode.CEILING);
    }

    private record ModelPrice(
            BigDecimal inputPerToken,
            BigDecimal outputPerToken
    ) {
    }
}
