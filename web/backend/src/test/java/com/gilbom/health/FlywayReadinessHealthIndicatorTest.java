package com.gilbom.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;

class FlywayReadinessHealthIndicatorTest {

    @Test
    void reportsCompletedAfterTheInitializerWasInjected() {
        FlywayMigrationInitializer initializer =
                mock(FlywayMigrationInitializer.class);
        FlywayReadinessHealthIndicator indicator =
                new FlywayReadinessHealthIndicator(initializer);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("migration", "complete");
    }
}
