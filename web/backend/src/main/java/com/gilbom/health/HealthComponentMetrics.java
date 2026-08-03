package com.gilbom.health;

import java.util.Locale;
import java.util.Map;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * 각 health contributor의 상태를 Prometheus gauge로 낸다.
 *
 * <p>{@code /actuator/health}는 사람이 보는 endpoint이고 Prometheus는 그 응답을
 * 해석하지 않는다. 그래서 컨테이너 생존 여부(up)만으로는 "Backend Down"과
 * "Storage Readiness 실패"를 구분해 알람할 수 없다. 구성요소별로
 * {@code gilbom_health_component_status{component="storageReadiness"}}를 내보내
 * 두 경우를 나눈다.
 *
 * <p>값은 UP이면 1, 그 외에는 0이다. Gauge는 scrape 시점에 평가되므로 수집 주기가
 * 곧 health 확인 주기이며, {@code db} contributor의 검증 질의도 그 주기로만 실행된다.
 *
 * <p>component 이름은 actuator가 health 응답에 쓰는 이름과 같게 맞춘다. actuator는
 * bean 이름에서 {@code HealthIndicator} 접미사를 떼므로({@code dbHealthIndicator} →
 * {@code db}) 여기서도 같은 규칙을 적용한다.
 */
@Component
public final class HealthComponentMetrics implements MeterBinder {

    private static final String METRIC_NAME = "gilbom.health.component.status";
    private static final String BEAN_NAME_SUFFIX = "healthindicator";

    private final Map<String, HealthIndicator> indicators;

    public HealthComponentMetrics(Map<String, HealthIndicator> indicators) {
        this.indicators = indicators;
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        indicators.forEach((beanName, indicator) ->
                Gauge.builder(METRIC_NAME, indicator, HealthComponentMetrics::statusValue)
                        .tag("component", componentName(beanName))
                        .description("1 when the health contributor reports UP, otherwise 0")
                        .strongReference(true)
                        .register(meterRegistry));
    }

    private static String componentName(String beanName) {
        String lowered = beanName.toLowerCase(Locale.ROOT);
        if (lowered.endsWith(BEAN_NAME_SUFFIX)
                && beanName.length() > BEAN_NAME_SUFFIX.length()) {
            return beanName.substring(0, beanName.length() - BEAN_NAME_SUFFIX.length());
        }
        return beanName;
    }

    private static double statusValue(HealthIndicator indicator) {
        try {
            return Status.UP.equals(indicator.health().getStatus()) ? 1.0d : 0.0d;
        } catch (RuntimeException ex) {
            // Contributor가 던지는 예외는 그 자체로 DOWN이다. 수집을 중단시키지 않는다.
            return 0.0d;
        }
    }
}
