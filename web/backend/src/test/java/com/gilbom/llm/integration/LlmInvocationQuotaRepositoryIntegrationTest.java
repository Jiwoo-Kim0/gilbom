package com.gilbom.llm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.llm.LlmInvocationQuotaRepository;
import com.gilbom.llm.LlmInvocationQuotaRepository.DenialReason;
import com.gilbom.llm.LlmInvocationQuotaRepository.Reservation;
import com.gilbom.llm.LlmInvocationQuotaRepository.ReservationResult;
import com.gilbom.llm.LlmUsage;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes =
                LlmInvocationQuotaRepositoryIntegrationTest
                        .TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class LlmInvocationQuotaRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final LocalDate USAGE_DATE =
            LocalDate.of(2026, 7, 30);
    private static final Instant NOW =
            Instant.parse("2026-07-30T01:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_llm_quota_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add(
                "spring.datasource.username",
                DATABASE::getUsername);
        registry.add(
                "spring.datasource.password",
                DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add(
                "spring.flyway.password",
                DATABASE::getPassword);
        registry.add(
                "spring.flyway.default-schema",
                () -> "flyway_history");
        registry.add(
                "spring.flyway.placeholders.applicationRole",
                DATABASE::getUsername);
    }

    @Autowired
    private LlmInvocationQuotaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearLedger() {
        jdbcTemplate.update(
                "DELETE FROM request_control.llm_invocation_usage");
    }

    @Test
    void enforcesPerUserDailyLimitAndGlobalBudget() {
        UUID firstUser = UUID.randomUUID();
        ReservationResult first = reserve(
                firstUser,
                1,
                "300",
                "100");
        ReservationResult userLimited = reserve(
                firstUser,
                1,
                "300",
                "100");
        ReservationResult budgetLimited = reserve(
                UUID.randomUUID(),
                1,
                "150",
                "100");

        assertThat(first.allowed()).isTrue();
        assertThat(userLimited.allowed()).isFalse();
        assertThat(userLimited.denialReason())
                .isEqualTo(DenialReason.USER_DAILY_LIMIT);
        assertThat(budgetLimited.allowed()).isFalse();
        assertThat(budgetLimited.denialReason())
                .isEqualTo(DenialReason.DAILY_BUDGET);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void concurrentBudgetReservationsAllowOnlyOneWinner()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor =
                Executors.newFixedThreadPool(2)) {
            List<Future<ReservationResult>> futures =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                start.await(5, TimeUnit.SECONDS);
                                return reserve(
                                        UUID.randomUUID(),
                                        10,
                                        "100",
                                        "80");
                            }))
                            .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .isTrue();
            start.countDown();

            List<ReservationResult> results =
                    futures.stream()
                            .map(future -> {
                                try {
                                    return future.get(
                                            10,
                                            TimeUnit.SECONDS);
                                } catch (Exception exception) {
                                    throw new AssertionError(exception);
                                }
                            })
                            .toList();
            assertThat(results.stream()
                    .filter(ReservationResult::allowed))
                    .hasSize(1);
            assertThat(results.stream()
                    .filter(result -> !result.allowed())
                    .map(ReservationResult::denialReason))
                    .containsExactly(DenialReason.DAILY_BUDGET);
            assertThat(count()).isEqualTo(1);
        }
    }

    @Test
    void storesOnlySafeUsageMetadataOnCompletion() {
        Reservation reservation = reserve(
                UUID.randomUUID(),
                5,
                "300",
                "221.25")
                .reservation();
        repository.complete(
                reservation,
                "SUCCEEDED",
                new BigDecimal("27.750000"),
                new LlmUsage(500, 300, 800),
                null,
                null,
                NOW.plusSeconds(2));

        java.util.Map<String, Object> stored =
                jdbcTemplate.queryForMap("""
                        SELECT
                            attempt_status,
                            accounted_credits,
                            input_tokens,
                            output_tokens,
                            total_tokens,
                            failure_code,
                            provider_http_status
                        FROM request_control.llm_invocation_usage
                        WHERE attempt_id = ?
                        """,
                        reservation.attemptId());
        assertThat(stored)
                .containsEntry("attempt_status", "SUCCEEDED")
                .containsEntry(
                        "accounted_credits",
                        new BigDecimal("27.750000"))
                .containsEntry("input_tokens", 500)
                .containsEntry("output_tokens", 300)
                .containsEntry("total_tokens", 800)
                .containsEntry("failure_code", null)
                .containsEntry("provider_http_status", null);
        assertThat(columnNames())
                .doesNotContain(
                        "prompt",
                        "response",
                        "request_body",
                        "image_bytes",
                        "authorization");
    }

    @Test
    void storesOnlySafeProviderStatusForRejectedRequest() {
        Reservation reservation = reserve(
                UUID.randomUUID(),
                5,
                "300",
                "221.25")
                .reservation();

        repository.complete(
                reservation,
                "FAILED",
                reservation.reservedCredits(),
                null,
                "PROVIDER_4XX",
                413,
                NOW.plusSeconds(1));

        java.util.Map<String, Object> stored =
                jdbcTemplate.queryForMap("""
                SELECT
                    attempt_status,
                    failure_code,
                    provider_http_status
                FROM request_control.llm_invocation_usage
                WHERE attempt_id = ?
                """,
                reservation.attemptId());
        assertThat(stored)
                .containsEntry("attempt_status", "FAILED")
                .containsEntry("failure_code", "PROVIDER_4XX");
        assertThat(((Number) stored.get(
                "provider_http_status")).intValue())
                .isEqualTo(413);
    }

    private ReservationResult reserve(
            UUID userId,
            int limit,
            String budget,
            String credits
    ) {
        return repository.reserve(
                USAGE_DATE,
                userId,
                "GMS",
                "gpt-5.4-mini",
                limit,
                new BigDecimal(budget),
                new BigDecimal(credits),
                NOW);
    }

    private int count() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM "
                        + "request_control.llm_invocation_usage",
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> columnNames() {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'request_control'
                  AND table_name = 'llm_invocation_usage'
                """,
                String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LlmInvocationQuotaRepository.class)
    static class TestApplication {
    }
}
