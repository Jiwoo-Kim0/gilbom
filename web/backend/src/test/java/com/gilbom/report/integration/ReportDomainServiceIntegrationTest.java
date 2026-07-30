package com.gilbom.report.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.BestEffortAuditLogWriter;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.AuthenticationSessionConfiguration;
import com.gilbom.common.concurrency.ExpectedVersionGuard;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.idempotency.CanonicalRequestFingerprint;
import com.gilbom.common.idempotency.IdempotencyRequestStore;
import com.gilbom.report.CreatedReport;
import com.gilbom.report.ReportActor;
import com.gilbom.report.ReportContent;
import com.gilbom.report.ReportCreateCommand;
import com.gilbom.report.ReportCreationIdempotencyRepository;
import com.gilbom.report.ReportDomainService;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportGenerationFallbackReason;
import com.gilbom.report.ReportRecord;
import com.gilbom.report.ReportRepository;
import com.gilbom.report.ReportStateTransitionService;
import com.gilbom.report.ReportStatus;
import com.gilbom.report.ReportType;
import com.gilbom.report.pdf.ApprovedReportPdfService;
import com.gilbom.report.pdf.KoreanApprovedReportPdfRenderer;
import com.gilbom.report.pdf.LocalApprovedReportPdfStorage;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = ReportDomainServiceIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class ReportDomainServiceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final UUID AUTHOR_ID =
            UUID.fromString("8b0fa604-0db2-4cc6-99b2-963e2c38e701");
    private static final UUID REVIEWER_ID =
            UUID.fromString("8b0fa604-0db2-4cc6-99b2-963e2c38e702");
    private static final Instant PERIOD_FROM =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant PERIOD_TO =
            Instant.parse("2026-07-07T23:59:59Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_report_domain_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
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
    private ReportDomainService service;

    @Autowired
    private ReportRepository repository;

    @Autowired
    private ReportCreationIdempotencyRepository
            idempotencyRepository;

    @Autowired
    private IdempotencyRequestStore idempotencyRequestStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createUsers() {
        createUser(
                AUTHOR_ID,
                "USR-REPORT-AUTHOR",
                "report-author@example.test");
        createUser(
                REVIEWER_ID,
                "USR-REPORT-REVIEWER",
                "report-reviewer@example.test");
    }

    @Test
    void createsOnceAndReplaysTheStoredResponseForTwentyFourHours()
            throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        ReportCreateCommand command = createCommand("주간 결함 보고서");

        CreatedReport first = service.create(
                command,
                author(),
                idempotencyKey,
                requestContext());
        CreatedReport replay = service.create(
                command,
                author(),
                idempotencyKey,
                requestContext());

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.report().reportId())
                .isEqualTo(first.report().reportId());
        assertThat(first.report().status())
                .isEqualTo(ReportStatus.DRAFT);
        assertThat(first.report().currentVersionNo()).isEqualTo(1);

        Map<String, Object> idempotency = jdbcTemplate.queryForMap("""
                SELECT
                    processing_status,
                    response_status,
                    resource_type,
                    resource_id,
                    EXTRACT(
                        EPOCH FROM (expires_at - created_at)
                    )::BIGINT AS ttl_seconds
                FROM request_control.idempotency_request
                WHERE resource_id = ?
                """,
                first.report().reportId());
        assertThat(idempotency)
                .containsEntry("processing_status", "SUCCEEDED")
                .containsEntry("response_status", 201)
                .containsEntry("resource_type", "REPORT")
                .containsEntry(
                        "resource_id",
                        first.report().reportId())
                .containsEntry("ttl_seconds", 86_400L);

        assertThat(auditCount(
                first.report().reportId(),
                "REPORT_CREATED")).isEqualTo(1);
        assertThat(versionCount(first.report().reportId())).isEqualTo(1);

        service.update(
                first.report().reportId(),
                1,
                content("생성 후 수정", "수정된 현재 본문", null),
                author(),
                requestContext());
        CreatedReport replayAfterUpdate = service.create(
                command,
                author(),
                idempotencyKey,
                requestContext());
        assertThat(replayAfterUpdate.created()).isFalse();
        assertThat(replayAfterUpdate.report().status())
                .isEqualTo(ReportStatus.DRAFT);
        assertThat(replayAfterUpdate.report().currentVersionNo())
                .isEqualTo(1);
        assertThat(replayAfterUpdate.report().title())
                .isEqualTo("주간 결함 보고서");
        assertThat(service.findActive(first.report().reportId()).title())
                .isEqualTo("생성 후 수정");

        ReportCreateCommand differentBody =
                createCommand("다른 제목의 보고서");
        assertStateError(
                () -> service.create(
                        differentBody,
                        author(),
                        idempotencyKey,
                        requestContext()),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);

        CreatedReport changedRequest = service.create(
                differentBody,
                author(),
                UUID.randomUUID(),
                requestContext());
        assertThat(changedRequest.created()).isTrue();
        assertThat(changedRequest.report().reportId())
                .isNotEqualTo(first.report().reportId());

        CreatedReport otherUser = service.create(
                command,
                reviewer(),
                idempotencyKey,
                requestContext());
        assertThat(otherUser.created()).isTrue();
        assertThat(otherUser.report().reportId())
                .isNotEqualTo(first.report().reportId());
    }

    @Test
    void committedProcessingClaimNeverCreatesADuplicate() {
        UUID idempotencyKey = UUID.randomUUID();
        ReportCreateCommand command =
                createCommand("처리 중 멱등 요청");
        byte[] principalHash = sha256(
                "USER\u0000" + AUTHOR_ID);
        byte[] keyHash = sha256(idempotencyKey.toString());
        byte[] fingerprint =
                idempotencyRepository.fingerprint(command);
        int reportsBefore = rowCount("report.anomaly_report");

        jdbcTemplate.update("""
                INSERT INTO request_control.idempotency_request (
                    principal_type,
                    principal_key_hash,
                    idempotency_key_hash,
                    request_fingerprint,
                    request_method,
                    request_path,
                    processing_status,
                    lease_expires_at,
                    expires_at
                ) VALUES (
                    'USER', ?, ?, ?,
                    'POST', '/api/v1/reports', 'PROCESSING',
                    NOW() + INTERVAL '1 minute',
                    NOW() + INTERVAL '24 hours'
                )
                """,
                principalHash,
                keyHash,
                fingerprint);
        try {
            assertStateError(
                    () -> service.create(
                            command,
                            author(),
                            idempotencyKey,
                            requestContext()),
                    ErrorCode.COMMON_CONFLICT);
            assertThat(rowCount("report.anomaly_report"))
                    .isEqualTo(reportsBefore);
        } finally {
            jdbcTemplate.update("""
                    DELETE FROM request_control.idempotency_request
                    WHERE principal_key_hash = ?
                      AND idempotency_key_hash = ?
                    """,
                    principalHash,
                    keyHash);
        }
    }

    @Test
    void claimOwnerAloneBuildsGeneratedCommandAndReplaySkipsIt() {
        UUID idempotencyKey = UUID.randomUUID();
        Map<String, Object> requestBody = Map.of(
                "generationMethod", "LLM",
                "reportType", "PERIODIC",
                "periodFrom", PERIOD_FROM.toString(),
                "periodTo", PERIOD_TO.toString());
        AtomicInteger generated = new AtomicInteger();

        CreatedReport first = service.createClaimed(
                requestBody,
                author(),
                idempotencyKey,
                requestContext(),
                () -> {
                    generated.incrementAndGet();
                    return createCommand("claim 소유자 생성");
                });
        CreatedReport replay = service.createClaimed(
                requestBody,
                author(),
                idempotencyKey,
                requestContext(),
                () -> {
                    throw new AssertionError(
                            "replay must not rebuild LLM command");
                });

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.report().reportId())
                .isEqualTo(first.report().reportId());
        assertThat(generated).hasValue(1);
        assertThat(versionCount(first.report().reportId()))
                .isEqualTo(1);
        assertThat(auditCount(
                first.report().reportId(),
                "REPORT_CREATED")).isEqualTo(1);
    }

    @Test
    void persistsSafeFallbackReasonOnlyOnTemplateVersion() {
        ReportContent fallback = new ReportContent(
                "fallback 보고서",
                "테스트 요약",
                PERIOD_FROM,
                PERIOD_TO,
                "template 본문",
                Map.of("totalDefectCount", 0),
                ReportContent.emptyEvidence(),
                null,
                null,
                ReportGenerationMethod.TEMPLATE,
                null,
                ReportGenerationFallbackReason.LLM_UNAVAILABLE);
        ReportCreateCommand command = new ReportCreateCommand(
                ReportType.PERIODIC,
                Map.of(),
                fallback);

        ReportRecord created = service.create(
                        command,
                        author(),
                        UUID.randomUUID(),
                        requestContext())
                .report();

        assertThat(created.currentVersionGenerationMethod())
                .isEqualTo(ReportGenerationMethod.TEMPLATE);
        assertThat(created.currentVersionGenerationFallbackReason())
                .isEqualTo(ReportGenerationFallbackReason.LLM_UNAVAILABLE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT generation_fallback_reason
                FROM report.report_version
                WHERE report_id = ?
                  AND version_no = 1
                """,
                String.class,
                created.reportId()))
                .isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void cleanupDeletesExpiredIdempotencyRowsInBoundedBatches() {
        ReportRecord expired = createReport("만료 멱등 행");
        ReportRecord active = createReport("활성 멱등 행");
        jdbcTemplate.update("""
                UPDATE request_control.idempotency_request
                SET created_at = NOW() - INTERVAL '26 hours',
                    lease_expires_at =
                        NOW() - INTERVAL '25 hours',
                    expires_at = NOW() - INTERVAL '1 hour',
                    updated_at = NOW()
                WHERE resource_id = ?
                """,
                expired.reportId());

        assertThat(idempotencyRequestStore.deleteExpiredBatch(10))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                WHERE resource_id = ?
                """,
                Integer.class,
                expired.reportId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM request_control.idempotency_request
                WHERE resource_id = ?
                """,
                Integer.class,
                active.reportId())).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void concurrentDuplicateCreatesReturnOneCreationAndOneReplay()
            throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        ReportCreateCommand command =
                createCommand("동시 생성 보고서");

        List<CreatedReport> attempts = race(
                () -> service.create(
                        command,
                        author(),
                        idempotencyKey,
                        requestContext()),
                () -> service.create(
                        command,
                        author(),
                        idempotencyKey,
                        requestContext()));

        assertThat(attempts)
                .extracting(CreatedReport::created)
                .containsExactlyInAnyOrder(true, false);
        assertThat(attempts)
                .extracting(attempt ->
                        attempt.report().reportId())
                .containsOnly(
                        attempts.getFirst().report().reportId());
        UUID reportId = attempts.getFirst().report().reportId();
        assertThat(versionCount(reportId)).isEqualTo(1);
        assertThat(auditCount(reportId, "REPORT_CREATED"))
                .isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void concurrentUpdatesWithOneExpectedVersionAllowOnlyOneWinner()
            throws Exception {
        ReportRecord created = createReport("동시 수정 대상");

        List<MutationAttempt> attempts = race(
                () -> capture(() -> service.update(
                        created.reportId(),
                        1,
                        content("수정안 A", "본문 A", "동시 수정 A"),
                        author(),
                        requestContext())),
                () -> capture(() -> service.update(
                        created.reportId(),
                        1,
                        content("수정안 B", "본문 B", "동시 수정 B"),
                        author(),
                        requestContext())));

        assertOneSuccessAndOneConflict(attempts);
        ReportRecord current =
                service.findActive(created.reportId());
        assertThat(current.currentVersionNo()).isEqualTo(2);
        assertThat(current.title()).isIn("수정안 A", "수정안 B");
        assertThat(versionCount(created.reportId())).isEqualTo(2);
        assertThat(auditCount(
                created.reportId(),
                "REPORT_UPDATED")).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void concurrentSubmitAndDecisionTransitionsAllowOnlyFirstCommit()
            throws Exception {
        ReportRecord created = createReport("동시 전이 대상");

        List<MutationAttempt> submitAttempts = race(
                () -> capture(() -> service.submit(
                        created.reportId(),
                        1,
                        author(),
                        requestContext())),
                () -> capture(() -> service.submit(
                        created.reportId(),
                        1,
                        author(),
                        requestContext())));
        assertOneSuccessAndOneConflict(submitAttempts);
        assertThat(service.findActive(created.reportId()).status())
                .isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(auditCount(
                created.reportId(),
                "REPORT_SUBMITTED")).isEqualTo(1);

        List<MutationAttempt> decisionAttempts = race(
                () -> capture(() -> service.approve(
                        created.reportId(),
                        1,
                        "승인",
                        reviewer(),
                        requestContext())),
                () -> capture(() -> service.reject(
                        created.reportId(),
                        1,
                        "반려",
                        reviewer(),
                        requestContext())));
        assertOneSuccessAndOneConflict(decisionAttempts);
        assertThat(service.findActive(created.reportId()).status())
                .isIn(ReportStatus.APPROVED, ReportStatus.REJECTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE target_type = 'REPORT'
                  AND target_id = ?
                  AND action_type IN (
                      'REPORT_APPROVED',
                      'REPORT_REJECTED'
                  )
                """,
                Integer.class,
                created.reportId())).isEqualTo(1);
    }

    @Test
    void revisionsAppendVersionsAndRejectedRevisionReturnsToDraft() {
        ReportRecord created = createReport("초안");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO report.report_version (
                    report_id,
                    version_no,
                    title,
                    summary,
                    period_from,
                    period_to,
                    content,
                    statistics,
                    evidence_snapshot,
                    llm_model,
                    prompt_version,
                    generation_method,
                    change_note,
                    creator_type,
                    created_by,
                    created_at
                )
                SELECT
                    report_id,
                    2,
                    title,
                    summary,
                    period_from,
                    period_to,
                    content,
                    statistics,
                    evidence_snapshot,
                    llm_model,
                    prompt_version,
                    generation_method,
                    'header pointer 없는 version',
                    creator_type,
                    created_by,
                    NOW()
                FROM report.report_version
                WHERE report_id = ?
                  AND version_no = 1
                """,
                created.reportId()))
                .isInstanceOf(DataAccessException.class);

        ReportRecord secondVersion = service.update(
                created.reportId(),
                1,
                content("수정 초안", "수정 본문", "첫 수정"),
                author(),
                requestContext());
        assertStateError(
                () -> service.update(
                        created.reportId(),
                        1,
                        content("오래된 수정", "오래된 본문", null),
                        author(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);
        ReportRecord submitted = service.submit(
                created.reportId(),
                2,
                author(),
                requestContext());
        ReportRecord rejected = service.reject(
                created.reportId(),
                2,
                "통계 근거를 보완해 주세요.",
                reviewer(),
                requestContext());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE report.anomaly_report
                SET report_status = 'DRAFT',
                    updated_at = NOW()
                WHERE report_id = ?
                """,
                created.reportId()))
                .isInstanceOf(DataAccessException.class);
        ReportRecord revised = service.update(
                created.reportId(),
                2,
                content("반려 보완본", "보완 본문", "반려 보완"),
                author(),
                requestContext());

        assertThat(secondVersion.currentVersionNo()).isEqualTo(2);
        assertThat(submitted.status())
                .isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(rejected.status())
                .isEqualTo(ReportStatus.REJECTED);
        assertThat(revised.status()).isEqualTo(ReportStatus.DRAFT);
        assertThat(revised.currentVersionNo()).isEqualTo(3);

        List<Map<String, Object>> versions =
                jdbcTemplate.queryForList("""
                        SELECT
                            version_no,
                            title,
                            content,
                            generation_method,
                            evidence_snapshot::text AS evidence
                        FROM report.report_version
                        WHERE report_id = ?
                        ORDER BY version_no
                        """,
                        created.reportId());
        assertThat(versions).hasSize(3);
        assertThat(versions.get(0))
                .containsEntry("version_no", 1)
                .containsEntry("title", "초안");
        assertThat(versions.get(1))
                .containsEntry("version_no", 2)
                .containsEntry("title", "수정 초안");
        assertThat(versions.get(2))
                .containsEntry("version_no", 3)
                .containsEntry("title", "반려 보완본")
                .containsEntry("generation_method", "MANUAL");
        assertThat(versions.get(2).get("evidence").toString())
                .contains("defectIds")
                .contains("missionIds")
                .contains("patrolAreaIds");

        assertThat(auditCount(
                created.reportId(),
                "REPORT_UPDATED")).isEqualTo(2);
        assertThat(auditCount(
                created.reportId(),
                "REPORT_SUBMITTED")).isEqualTo(1);
        assertThat(auditCount(
                created.reportId(),
                "REPORT_REJECTED")).isEqualTo(1);
    }

    @Test
    void blocksReviewAndApprovedMutationAndBindsPdfToApprovedVersion()
            throws Exception {
        ReportRecord created = createReport("승인 대상");
        ReportRecord inReview = service.submit(
                created.reportId(),
                1,
                author(),
                requestContext());

        assertStateError(
                () -> service.update(
                        created.reportId(),
                        1,
                        content("불가", "불가", null),
                        author(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);
        assertStateError(
                () -> service.softDelete(
                        created.reportId(),
                        1,
                        author(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);
        assertStateError(
                () -> service.approve(
                        created.reportId(),
                        1,
                        null,
                        author(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);

        ReportRecord approved = service.approve(
                created.reportId(),
                1,
                "통계와 근거 확인 완료",
                reviewer(),
                requestContext());

        assertThat(inReview.status())
                .isEqualTo(ReportStatus.IN_REVIEW);
        assertThat(approved.status())
                .isEqualTo(ReportStatus.APPROVED);
        assertThat(approved.approvedVersionNo()).isEqualTo(1);
        assertThat(approved.approvedBy()).isEqualTo(REVIEWER_ID);
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.approvedPdfVersionNo()).isEqualTo(1);
        assertThat(approved.approvedPdfStorageKey())
                .startsWith(
                        "reports/" + created.reportId() + "/v1/")
                .endsWith(".pdf");
        assertThat(approved.approvedPdfSha256()).hasSize(32);
        assertThat(approved.approvedPdfCreatedAt())
                .isEqualTo(approved.approvedAt());

        assertStateError(
                () -> service.update(
                        created.reportId(),
                        1,
                        content("불가", "불가", null),
                        author(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);
        assertStateError(
                () -> service.softDelete(
                        created.reportId(),
                        1,
                        reviewer(),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);

        ReportRecord withPdf = service.findActive(created.reportId());
        assertThat(withPdf.approvedPdfVersionNo()).isEqualTo(1);
        assertThat(withPdf.approvedPdfSha256())
                .containsExactly(approved.approvedPdfSha256());

        assertThatThrownBy(() -> repository.bindApprovedPdf(
                created.reportId(),
                1,
                "reports/" + created.reportId() + "/replacement.pdf",
                approved.approvedPdfSha256(),
                Instant.now()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void softDeleteKeepsVersionsButExcludesTheReportFromActiveQueries() {
        ReportRecord created = createReport("삭제 대상");

        service.softDelete(
                created.reportId(),
                1,
                author(),
                requestContext());

        assertStateError(
                () -> service.findActive(created.reportId()),
                ErrorCode.REPORT_NOT_FOUND);
        assertThat(service.findAllActive())
                .extracting(ReportRecord::reportId)
                .doesNotContain(created.reportId());
        assertThat(versionCount(created.reportId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deleted_at IS NOT NULL
                FROM report.anomaly_report
                WHERE report_id = ?
                """, Boolean.class, created.reportId())).isTrue();
        assertThat(auditCount(
                created.reportId(),
                "REPORT_DELETED")).isEqualTo(1);
    }

    @Test
    void rollsBackHeaderVersionIdempotencyAndAuditAsOneTransaction() {
        int reportsBefore = rowCount("report.anomaly_report");
        int versionsBefore = rowCount("report.report_version");
        int idempotencyBefore =
                rowCount("request_control.idempotency_request");
        int auditsBefore = rowCount("audit.audit_log");
        UUID idempotencyKey = UUID.randomUUID();
        ReportCreateCommand command =
                createCommand("원자성 검증");

        jdbcTemplate.execute("""
                CREATE FUNCTION audit.reject_report_created_for_test()
                RETURNS TRIGGER
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.action_type = 'REPORT_CREATED' THEN
                        RAISE EXCEPTION 'forced audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER tr_reject_report_created_for_test
                BEFORE INSERT ON audit.audit_log
                FOR EACH ROW
                EXECUTE FUNCTION audit.reject_report_created_for_test()
                """);
        try {
            assertThatThrownBy(() -> service.create(
                    command,
                    author(),
                    idempotencyKey,
                    requestContext()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER tr_reject_report_created_for_test
                    ON audit.audit_log
                    """);
            jdbcTemplate.execute("""
                    DROP FUNCTION audit.reject_report_created_for_test()
                    """);
        }

        assertThat(rowCount("report.anomaly_report"))
                .isEqualTo(reportsBefore);
        assertThat(rowCount("report.report_version"))
                .isEqualTo(versionsBefore);
        assertThat(rowCount("request_control.idempotency_request"))
                .isEqualTo(idempotencyBefore);
        assertThat(rowCount("audit.audit_log"))
                .isEqualTo(auditsBefore);

        CreatedReport retry = service.create(
                command,
                author(),
                idempotencyKey,
                requestContext());
        assertThat(retry.created()).isTrue();
        assertThat(versionCount(retry.report().reportId()))
                .isEqualTo(1);
        assertThat(auditCount(
                retry.report().reportId(),
                "REPORT_CREATED")).isEqualTo(1);
    }

    private ReportRecord createReport(String title) {
        return service.create(
                        createCommand(title),
                        author(),
                        UUID.randomUUID(),
                        requestContext())
                .report();
    }

    private static ReportCreateCommand createCommand(String title) {
        return new ReportCreateCommand(
                ReportType.PERIODIC,
                Map.of("officeIds", List.of("SEOUL-HQ")),
                content(title, title + " 본문", null));
    }

    private static ReportContent content(
            String title,
            String body,
            String changeNote
    ) {
        return new ReportContent(
                title,
                "테스트 요약",
                PERIOD_FROM,
                PERIOD_TO,
                body,
                Map.of(
                        "totalDefectCount", 1,
                        "highRiskCount", 1),
                Map.of(
                        "defectIds",
                        List.of(UUID.randomUUID().toString()),
                        "missionIds",
                        List.of(UUID.randomUUID().toString()),
                        "patrolAreaIds",
                        List.of(UUID.randomUUID().toString())),
                null,
                null,
                ReportGenerationMethod.MANUAL,
                changeNote);
    }

    private static ReportActor author() {
        return ReportActor.user(AUTHOR_ID);
    }

    private static ReportActor reviewer() {
        return ReportActor.user(REVIEWER_ID);
    }

    private static AuditRequestContext requestContext() {
        return AuditRequestContext.requestIdOnly(UUID.randomUUID());
    }

    private static MutationAttempt capture(
            Supplier<ReportRecord> action
    ) {
        try {
            return new MutationAttempt(action.get(), null);
        } catch (BusinessException exception) {
            return new MutationAttempt(null, exception.errorCode());
        }
    }

    private static void assertOneSuccessAndOneConflict(
            List<MutationAttempt> attempts
    ) {
        assertThat(attempts)
                .filteredOn(attempt -> attempt.result() != null)
                .hasSize(1);
        assertThat(attempts)
                .extracting(MutationAttempt::errorCode)
                .containsExactlyInAnyOrder(
                        null,
                        ErrorCode.REPORT_STATE_CONFLICT);
    }

    private static <T> List<T> race(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<T> firstResult = executor.submit(
                    gated(first, ready, start));
            Future<T> secondResult = executor.submit(
                    gated(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> Callable<T> gated(
            Callable<T> action,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrent test start timed out");
            }
            return action.call();
        };
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int versionCount(UUID reportId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM report.report_version
                WHERE report_id = ?
                """,
                Integer.class,
                reportId);
    }

    private int auditCount(UUID reportId, String action) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit.audit_log
                WHERE target_type = 'REPORT'
                  AND target_id = ?
                  AND action_type = ?
                """,
                Integer.class,
                reportId,
                action);
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private void createUser(
            UUID userId,
            String userCode,
            String email
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth.admin_user (
                    user_id,
                    user_code,
                    password_hash,
                    display_name,
                    email,
                    is_active,
                    account_status,
                    password_change_required,
                    password_changed_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    TRUE,
                    'ACTIVE',
                    FALSE,
                    NOW()
                )
                ON CONFLICT (user_id) DO NOTHING
                """,
                userId,
                userCode,
                passwordEncoder.encode(
                        "report-integration-only-password"),
                userCode,
                email);
    }

    private static void assertStateError(
            Runnable action,
            ErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(expected));
    }

    private record MutationAttempt(
            ReportRecord result,
            ErrorCode errorCode
    ) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            ReportDomainService.class,
            ReportRepository.class,
            ReportCreationIdempotencyRepository.class,
            ReportStateTransitionService.class,
            ExpectedVersionGuard.class,
            CanonicalRequestFingerprint.class,
            IdempotencyRequestStore.class,
            AuditLogService.class,
            AuditLogRepository.class,
            BestEffortAuditLogWriter.class,
            ApprovedReportPdfService.class,
            KoreanApprovedReportPdfRenderer.class,
            LocalApprovedReportPdfStorage.class,
            AuthenticationSessionConfiguration.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
