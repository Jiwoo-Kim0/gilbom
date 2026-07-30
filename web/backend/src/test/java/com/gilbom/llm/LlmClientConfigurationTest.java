package com.gilbom.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class LlmClientConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class))
                    .withUserConfiguration(
                            LlmClientConfiguration.class);

    @Test
    void defaultsToDisabledClientWithoutFailingStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmClient.class))
                    .isInstanceOf(DisabledLlmClient.class);
            assertThat(context.getBean(
                    LlmPolicyDecision.class).enabled())
                    .isFalse();
            assertThat(context.getBean(
                    LlmProperties.class)
                    .isImageInputAllowed()).isFalse();
        });
    }

    @Test
    void missingPolicyUsesDisabledClientEvenWhenRequestedEnabled() {
        contextRunner
                .withPropertyValues(
                        "gilbom.llm.enabled=true",
                        "gilbom.llm.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmClient.class))
                            .isInstanceOf(
                                    DisabledLlmClient.class);
                    assertThat(context.getBean(
                            LlmPolicyDecision.class)
                            .violations())
                            .contains(
                                    LlmPolicyViolation
                                            .MODEL_NOT_ALLOWED,
                                    LlmPolicyViolation
                                            .POLICY_NOT_APPROVED);
                });
    }

    @Test
    void completePolicyBuildsGmsClient() {
        contextRunner
                .withPropertyValues(approvedPropertyValues())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmClient.class))
                            .isInstanceOf(GmsLlmClient.class);
                    assertThat(context.getBean(
                            LlmPolicyDecision.class).enabled())
                            .isTrue();
                    assertThat(context.getBean(
                            LlmProperties.class)
                            .isImageInputAllowed()).isTrue();
                });
    }

    @Test
    void fakeClientOverridesExternalClientForTests() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(
                        FakeConfiguration.class,
                        LlmClientConfiguration.class)
                .withPropertyValues(approvedPropertyValues())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmClient.class))
                            .isSameAs(context.getBean(
                                    FakeLlmClient.class));
                    assertThat(context)
                            .doesNotHaveBean(GmsLlmClient.class);
                });
    }

    private static String[] approvedPropertyValues() {
        return new String[] {
                "gilbom.llm.enabled=true",
                "gilbom.llm.provider=GMS",
                "gilbom.llm.base-url="
                        + LlmPolicyGate.GMS_BASE_URL,
                "gilbom.llm.model=gpt-5.4-mini",
                "gilbom.llm.api-key=test-only-key",
                "gilbom.llm.connect-timeout=3s",
                "gilbom.llm.read-timeout=30s",
                "gilbom.llm.max-output-tokens=1500",
                "gilbom.llm.daily-request-limit=20",
                "gilbom.llm.daily-budget=2250",
                "gilbom.llm.prompt-version=report-v1",
                "gilbom.llm.policy-approved=true",
                "gilbom.llm.training-exclusion-approved=true",
                "gilbom.llm.data-retention-approved=true",
                "gilbom.llm.outbound-allowlist-applied=true",
                "gilbom.llm.image-input-enabled=true",
                "gilbom.llm.image-deidentification-approved=true"
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeConfiguration {

        @Bean
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }
}
