package com.gilbom.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HealthComponentMetricsTest {

    private static final String METRIC_NAME = "gilbom.health.component.status";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MetricsTestConfiguration.class, ContributorConfiguration.class);

    /**
     * 이 테스트가 존재하는 이유. actuator는 {@code db}를 반환형이 {@code HealthContributor}인
     * {@code @Bean} 메서드로 선언한다. Spring은 아직 만들어지지 않은 bean의 타입을 그 선언
     * 반환형으로 판단하므로, {@code Map<String, HealthIndicator>}로 주입받으면 {@code db}가
     * <b>조용히 빠진다.</b> 그러면 DB가 죽어도 시계열 자체가 없어 알람 규칙이 걸리지 않는다.
     * 운영에서 실제로 그렇게 누락돼 있었고, {@code /actuator/health}는 정상으로 보였다.
     */
    @Test
    void publishesGaugeForBeanDeclaredAsHealthContributor() {
        contextRunner.run(context ->
                assertThat(gauge(context.getBean(MeterRegistry.class), "db"))
                        .as("db health gauge")
                        .isNotNull());
    }

    @Test
    void stripsBothIndicatorAndContributorSuffixes() {
        contextRunner.run(context ->
                assertThat(componentNames(context.getBean(MeterRegistry.class)))
                        .contains("db", "storageReadiness", "noSuffix"));
    }

    @Test
    void reportsOneWhenIndicatorIsUp() {
        contextRunner.run(context ->
                assertThat(gauge(context.getBean(MeterRegistry.class), "storageReadiness").value())
                        .isEqualTo(1.0d));
    }

    /** Composite는 자기 상태가 없다. 하나라도 DOWN이면 부모 이름 하나로 DOWN이 보여야 한다. */
    @Test
    void compositeIsDownWhenAnyChildIsDown() {
        contextRunner.run(context ->
                assertThat(gauge(context.getBean(MeterRegistry.class), "group").value())
                        .isZero());
    }

    /** Contributor가 던지는 예외는 그 자체로 DOWN이다. 다른 gauge 수집을 막아서도 안 된다. */
    @Test
    void reportsZeroWhenIndicatorThrows() {
        contextRunner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            assertThat(gauge(registry, "exploding").value()).isZero();
            assertThat(gauge(registry, "storageReadiness").value()).isEqualTo(1.0d);
        });
    }

    private static io.micrometer.core.instrument.Gauge gauge(MeterRegistry registry, String component) {
        return registry.find(METRIC_NAME).tag("component", component).gauge();
    }

    private static List<String> componentNames(MeterRegistry registry) {
        return registry.find(METRIC_NAME).gauges().stream()
                .map(gauge -> gauge.getId().getTag("component"))
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class ContributorConfiguration {

        /** actuator의 {@code DataSourceHealthContributorAutoConfiguration}과 같은 선언 형태다. */
        @Bean
        HealthContributor dbHealthContributor() {
            return (HealthIndicator) () -> Health.up().build();
        }

        @Bean
        HealthIndicator storageReadinessHealthIndicator() {
            return () -> Health.up().build();
        }

        @Bean
        HealthContributor noSuffix() {
            return (HealthIndicator) () -> Health.up().build();
        }

        @Bean
        HealthContributor groupHealthContributor() {
            return CompositeHealthContributor.fromMap(Map.of(
                    "healthy", (HealthContributor) (HealthIndicator) () -> Health.up().build(),
                    "broken", (HealthContributor) (HealthIndicator) () -> Health.down().build()));
        }

        @Bean
        HealthIndicator explodingHealthIndicator() {
            return () -> {
                throw new IllegalStateException("boom");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsTestConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * MeterBinder 자동설정을 끌어오지 않고 테스트에서 직접 bind한다. 검증 대상은
         * bind가 언제 일어나는지가 아니라 어떤 contributor가 gauge로 나오는지다.
         */
        @Bean
        HealthComponentMetrics healthComponentMetrics(
                Map<String, HealthContributor> contributors, MeterRegistry meterRegistry) {
            HealthComponentMetrics metrics = new HealthComponentMetrics(contributors);
            metrics.bindTo(meterRegistry);
            return metrics;
        }
    }
}
