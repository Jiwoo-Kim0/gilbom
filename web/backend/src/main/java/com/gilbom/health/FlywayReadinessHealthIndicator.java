package com.gilbom.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.stereotype.Component;

/**
 * Flyway 초기화가 끝난 application context에서만 생성되는 readiness marker다.
 *
 * <p>Migration 실패 시 {@link FlywayMigrationInitializer} 자체가 context 기동을
 * 실패시키므로 이 contributor는 UP이 될 수 없다. 완료 상태를 메모리에 유지해
 * 매 health 요청마다 schema history를 다시 조회하지 않는다. 이후 DB 장애는
 * 별도의 {@code db} contributor가 반영한다.
 */
@Component("flywayReadiness")
public final class FlywayReadinessHealthIndicator
        implements HealthIndicator {

    public FlywayReadinessHealthIndicator(
            FlywayMigrationInitializer completedInitializer
    ) {
        // 완전히 초기화된 bean만 주입되므로 생성 시점에 migration이 완료돼 있다.
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("migration", "complete")
                .build();
    }
}
