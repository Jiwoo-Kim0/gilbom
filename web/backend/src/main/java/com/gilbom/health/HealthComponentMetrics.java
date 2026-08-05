package com.gilbom.health;

import java.util.Locale;
import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
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
 * bean 이름에서 {@code HealthIndicator}/{@code HealthContributor} 접미사를 떼므로
 * ({@code dbHealthContributor} → {@code db}) 여기서도 같은 규칙을 적용한다.
 *
 * <p>주입 타입은 {@link HealthIndicator}가 아니라 상위 타입인 {@link HealthContributor}다.
 * {@code db}는 factory method의 선언 반환형이 {@code HealthContributor}라서
 * {@code Map<String, HealthIndicator>}로 받으면 <b>조용히 빠진다.</b> 그러면 DB가 죽어도
 * 해당 시계열이 아예 없어 알람 규칙이 걸리지 않는다. 실제로 그렇게 누락된 적이 있다.
 */
@Component
public final class HealthComponentMetrics implements MeterBinder {

    private static final String METRIC_NAME = "gilbom.health.component.status";
    private static final String[] BEAN_NAME_SUFFIXES = {"healthindicator", "healthcontributor"};

    private final Map<String, HealthContributor> contributors;

    public HealthComponentMetrics(Map<String, HealthContributor> contributors) {
        this.contributors = contributors;
    }

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        contributors.forEach((beanName, contributor) ->
                Gauge.builder(METRIC_NAME, contributor, HealthComponentMetrics::statusValue)
                        .tag("component", componentName(beanName))
                        .description("1 when the health contributor reports UP, otherwise 0")
                        .strongReference(true)
                        .register(meterRegistry));
    }

    private static String componentName(String beanName) {
        String lowered = beanName.toLowerCase(Locale.ROOT);
        for (String suffix : BEAN_NAME_SUFFIXES) {
            if (lowered.endsWith(suffix) && beanName.length() > suffix.length()) {
                return beanName.substring(0, beanName.length() - suffix.length());
            }
        }
        return beanName;
    }

    private static double statusValue(HealthContributor contributor) {
        try {
            return isUp(contributor) ? 1.0d : 0.0d;
        } catch (RuntimeException ex) {
            // Contributor가 던지는 예외는 그 자체로 DOWN이다. 수집을 중단시키지 않는다.
            return 0.0d;
        }
    }

    /**
     * Composite는 자기 자신의 상태를 갖지 않는다. 하나라도 UP이 아니면 DOWN으로 접어
     * 부모 이름 하나로 알람할 수 있게 한다. 비어 있는 composite는 판단 근거가 없으므로
     * DOWN으로 두지 않는다.
     */
    private static boolean isUp(HealthContributor contributor) {
        if (contributor instanceof HealthIndicator indicator) {
            return Status.UP.equals(indicator.health().getStatus());
        }
        if (contributor instanceof CompositeHealthContributor composite) {
            boolean up = true;
            for (NamedContributor<HealthContributor> child : composite) {
                up = isUp(child.getContributor()) && up;
            }
            return up;
        }
        return true;
    }
}
