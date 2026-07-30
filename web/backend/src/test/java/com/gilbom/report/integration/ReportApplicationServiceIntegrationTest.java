package com.gilbom.report.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import com.gilbom.auth.mfa.AuthenticationUserRepository;
import com.gilbom.auth.password.PasswordHashingConfiguration;
import com.gilbom.auth.session.AuthenticationSessionConfiguration;
import com.gilbom.common.concurrency.ExpectedVersionGuard;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.common.idempotency.CanonicalRequestFingerprint;
import com.gilbom.common.idempotency.IdempotencyRequestStore;
import com.gilbom.report.ReportAccessService;
import com.gilbom.report.ReportActor;
import com.gilbom.report.ReportCommandService;
import com.gilbom.report.ReportCreationIdempotencyRepository;
import com.gilbom.report.ReportDomainService;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportListFilter;
import com.gilbom.report.ReportQueryRepository;
import com.gilbom.report.ReportQueryService;
import com.gilbom.report.ReportRecord;
import com.gilbom.report.ReportRepository;
import com.gilbom.report.ReportResponseMapper;
import com.gilbom.report.ReportStateTransitionService;
import com.gilbom.report.ReportStatus;
import com.gilbom.report.ReportType;
import com.gilbom.report.ReportDtos.ManualReportCreateRequest;
import com.gilbom.report.ReportDtos.PageResponse;
import com.gilbom.report.ReportDtos.ReportDetailResponse;
import com.gilbom.report.ReportDtos.ReportListItemResponse;
import com.gilbom.report.ReportDtos.ReportUpdateRequest;
import com.gilbom.report.ReportDtos.ReportVersionSummaryResponse;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = ReportApplicationServiceIntegrationTest.TestApplication.class,
        properties = {
                "spring.profiles.default=test",
                "spring.jpa.hibernate.ddl-auto=none"
        })
class ReportApplicationServiceIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final Instant JULY_1 =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant JULY_7 =
            Instant.parse("2026-07-07T23:59:59Z");

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_report_api_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private final UUID authorId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID noPermissionUserId = UUID.randomUUID();

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
    private ReportCommandService commandService;

    @Autowired
    private ReportQueryService queryService;

    @Autowired
    private ReportDomainService domainService;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createUsersAndRoles() {
        createUser(authorId, "REPORT-AUTHOR");
        createUser(reviewerId, "REPORT-REVIEWER");
        createUser(noPermissionUserId, "REPORT-NONE");
        assignRole(authorId, "OPERATOR");
        assignRole(reviewerId, "MANAGER");
    }

    @Test
    void listsWithAllFiltersAndStablePagination() {
        ReportDetailResponse alpha = create(
                "알파 주간 수동",
                ReportGenerationMethod.MANUAL,
                ReportType.PERIODIC,
                JULY_1,
                JULY_7);
        create(
                "베타 월간 템플릿",
                ReportGenerationMethod.TEMPLATE,
                ReportType.PERIODIC,
                Instant.parse("2026-07-10T00:00:00Z"),
                Instant.parse("2026-07-20T23:59:59Z"));
        create(
                "감마 이슈 수동",
                ReportGenerationMethod.MANUAL,
                ReportType.ISSUE,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z"));

        PageResponse<ReportListItemResponse> filtered =
                queryService.findReports(
                        authorId,
                        0,
                        20,
                        ReportListFilter.of(
                                "주간",
                                List.of(ReportStatus.DRAFT),
                                authorId,
                                Instant.parse(
                                        "2026-07-05T00:00:00Z"),
                                Instant.parse(
                                        "2026-07-05T23:59:59Z"),
                                ReportType.PERIODIC,
                                ReportGenerationMethod.MANUAL,
                                "title,asc"));

        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.content())
                .extracting(ReportListItemResponse::reportId)
                .containsExactly(alpha.report().reportId());

        PageResponse<ReportListItemResponse> firstPage =
                queryService.findReports(
                        authorId,
                        0,
                        2,
                        ReportListFilter.of(
                                null,
                                null,
                                authorId,
                                null,
                                null,
                                null,
                                null,
                                "title,asc"));
        PageResponse<ReportListItemResponse> secondPage =
                queryService.findReports(
                        authorId,
                        1,
                        2,
                        ReportListFilter.of(
                                null,
                                null,
                                authorId,
                                null,
                                null,
                                null,
                                null,
                                "title,asc"));

        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content())
                .extracting(ReportListItemResponse::title)
                .containsExactly("감마 이슈 수동", "알파 주간 수동");
        assertThat(secondPage.content())
                .extracting(ReportListItemResponse::title)
                .containsExactly("베타 월간 템플릿");
    }

    @Test
    void updateAppendsVersionAndPreservesOldSnapshot() {
        ReportDetailResponse created = create(
                "수정 전 제목",
                ReportGenerationMethod.TEMPLATE,
                ReportType.PERIODIC,
                JULY_1,
                JULY_7);
        UUID reportId = created.report().reportId();
        ReportUpdateRequest request = updateRequest(
                1,
                "수정 후 제목",
                "수정 후 본문",
                "수동 보완");

        ReportDetailResponse updated = commandService.update(
                authorId,
                reportId,
                request,
                requestContext());

        assertThat(updated.report().currentVersionNo()).isEqualTo(2);
        assertThat(updated.report().reportStatus())
                .isEqualTo(ReportStatus.DRAFT);
        assertThat(updated.report().updatedBy()).isEqualTo(authorId);
        assertThat(updated.currentVersion().content())
                .isEqualTo("수정 후 본문");
        assertThat(updated.currentVersion().version()
                .generationMethod())
                .isEqualTo(ReportGenerationMethod.MANUAL);
        assertThat(updated.currentVersion().version().changeNote())
                .isEqualTo("수동 보완");

        PageResponse<ReportVersionSummaryResponse> versions =
                queryService.findVersions(
                        authorId,
                        reportId,
                        0,
                        20);
        assertThat(versions.content())
                .extracting(ReportVersionSummaryResponse::versionNo)
                .containsExactly(2, 1);
        assertThat(queryService.findVersion(
                        authorId,
                        reportId,
                        1)
                .content()).isEqualTo("보고서 본문: 수정 전 제목");

        assertBusinessError(
                () -> commandService.update(
                        authorId,
                        reportId,
                        updateRequest(
                                1,
                                "충돌",
                                "노출되면 안 되는 본문",
                                null),
                        requestContext()),
                ErrorCode.REPORT_STATE_CONFLICT);
        ReportUpdateRequest emptyUpdate =
                new ReportUpdateRequest();
        emptyUpdate.setExpectedVersionNo(2);
        assertBusinessError(
                () -> commandService.update(
                        authorId,
                        reportId,
                        emptyUpdate,
                        requestContext()),
                ErrorCode.REPORT_VALIDATION_FAILED);
        assertThat(auditCount(reportId, "REPORT_UPDATED"))
                .isEqualTo(1);
    }

    @Test
    void rejectedEditReturnsToDraftAndSoftDeleteKeepsHistory() {
        ReportDetailResponse created = create(
                "반려 대상",
                ReportGenerationMethod.MANUAL,
                ReportType.ISSUE,
                JULY_1,
                JULY_7);
        UUID reportId = created.report().reportId();
        domainService.submit(
                reportId,
                1,
                ReportActor.user(authorId),
                requestContext());
        domainService.reject(
                reportId,
                1,
                "근거 결함을 보완하세요.",
                ReportActor.user(reviewerId),
                requestContext());

        ReportDetailResponse rejected =
                queryService.findReport(authorId, reportId);
        assertThat(rejected.report().reportStatus())
                .isEqualTo(ReportStatus.REJECTED);
        assertThat(rejected.lastRejectionReason())
                .isEqualTo("근거 결함을 보완하세요.");

        ReportDetailResponse edited = commandService.update(
                authorId,
                reportId,
                updateRequest(
                        1,
                        "반려 보완본",
                        "보완된 본문",
                        "반려 사유 반영"),
                requestContext());
        assertThat(edited.report().reportStatus())
                .isEqualTo(ReportStatus.DRAFT);
        assertThat(edited.report().currentVersionNo()).isEqualTo(2);
        assertThat(edited.lastRejectionReason())
                .isEqualTo("근거 결함을 보완하세요.");

        commandService.softDelete(
                authorId,
                reportId,
                2,
                requestContext());

        assertBusinessError(
                () -> queryService.findReport(authorId, reportId),
                ErrorCode.REPORT_NOT_FOUND);
        PageResponse<ReportListItemResponse> active =
                queryService.findReports(
                        authorId,
                        0,
                        20,
                        ReportListFilter.of(
                                null,
                                null,
                                authorId,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThat(active.content())
                .extracting(ReportListItemResponse::reportId)
                .doesNotContain(reportId);
        assertThat(versionCount(reportId)).isEqualTo(2);
        assertThat(auditCount(reportId, "REPORT_REJECTED"))
                .isEqualTo(1);
        assertThat(auditCount(reportId, "REPORT_UPDATED"))
                .isEqualTo(1);
        assertThat(auditCount(reportId, "REPORT_DELETED"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT deleted_at IS NOT NULL
                FROM report.anomaly_report
                WHERE report_id = ?
                """,
                Boolean.class,
                reportId)).isTrue();
    }

    @Test
    void serviceBoundaryUsesCurrentDatabasePermissions() {
        ReportDetailResponse created = create(
                "권한 검증",
                ReportGenerationMethod.MANUAL,
                ReportType.PERIODIC,
                JULY_1,
                JULY_7);

        assertBusinessError(
                () -> queryService.findReports(
                        noPermissionUserId,
                        0,
                        20,
                        ReportListFilter.of(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null)),
                ErrorCode.REPORT_ACCESS_DENIED);
        assertBusinessError(
                () -> commandService.create(
                        noPermissionUserId,
                        UUID.randomUUID(),
                        createRequest(
                                "권한 없는 생성",
                                ReportGenerationMethod.MANUAL,
                                ReportType.PERIODIC,
                                JULY_1,
                                JULY_7),
                        requestContext()),
                ErrorCode.REPORT_ACCESS_DENIED);
        assertBusinessError(
                () -> commandService.update(
                        noPermissionUserId,
                        created.report().reportId(),
                        updateRequest(
                                1,
                                "권한 없는 수정",
                                "권한 없는 수정 본문",
                                null),
                        requestContext()),
                ErrorCode.REPORT_ACCESS_DENIED);
    }

    @Test
    void detailReturnsApprovalAndImmutablePdfIdentity() throws Exception {
        ReportDetailResponse created = create(
                "승인 보고서",
                ReportGenerationMethod.MANUAL,
                ReportType.PERIODIC,
                JULY_1,
                JULY_7);
        UUID reportId = created.report().reportId();
        domainService.submit(
                reportId,
                1,
                ReportActor.user(authorId),
                requestContext());
        ReportRecord approved = domainService.approve(
                reportId,
                1,
                "검토 완료",
                ReportActor.user(reviewerId),
                requestContext());
        byte[] sha256 = MessageDigest.getInstance("SHA-256")
                .digest("approved-pdf".getBytes(StandardCharsets.UTF_8));
        reportRepository.bindApprovedPdf(
                reportId,
                1,
                "reports/" + reportId + "/v1.pdf",
                sha256,
                approved.approvedAt().plusSeconds(1));

        ReportDetailResponse detail =
                queryService.findReport(authorId, reportId);

        assertThat(detail.report().reportStatus())
                .isEqualTo(ReportStatus.APPROVED);
        assertThat(detail.report().approvedVersionNo()).isEqualTo(1);
        assertThat(detail.report().approvedBy()).isEqualTo(reviewerId);
        assertThat(detail.report().approvedByDisplayName())
                .startsWith("REPORT-REVIEWER-");
        assertThat(detail.report().approvedAt()).isNotNull();
        assertThat(detail.approvedPdf().versionNo()).isEqualTo(1);
        assertThat(detail.approvedPdf().sha256())
                .isEqualTo(java.util.HexFormat.of()
                        .formatHex(sha256));
        assertThat(detail.approvedPdf().createdAt())
                .isAfterOrEqualTo(detail.report().approvedAt());
    }

    private ReportDetailResponse create(
            String title,
            ReportGenerationMethod method,
            ReportType type,
            Instant periodFrom,
            Instant periodTo
    ) {
        return commandService.create(
                        authorId,
                        UUID.randomUUID(),
                        createRequest(
                                title,
                                method,
                                type,
                                periodFrom,
                                periodTo),
                        requestContext())
                .report();
    }

    private static ManualReportCreateRequest createRequest(
            String title,
            ReportGenerationMethod method,
            ReportType type,
            Instant periodFrom,
            Instant periodTo
    ) {
        return new ManualReportCreateRequest(
                method,
                type,
                title,
                title + " 요약",
                periodFrom,
                periodTo,
                "보고서 본문: " + title,
                Map.of("defectCount", 3),
                Map.of("source", "integration-test"));
    }

    private static ReportUpdateRequest updateRequest(
            int expectedVersionNo,
            String title,
            String content,
            String changeNote
    ) {
        ReportUpdateRequest request = new ReportUpdateRequest();
        request.setExpectedVersionNo(expectedVersionNo);
        request.setTitle(title);
        request.setContent(content);
        request.setStatistics(Map.of("defectCount", 4));
        request.setChangeNote(changeNote);
        return request;
    }

    private void createUser(UUID userId, String codePrefix) {
        String suffix = userId.toString().substring(0, 8);
        String userCode = codePrefix + "-" + suffix;
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
                """,
                userId,
                userCode,
                passwordEncoder.encode(
                        "report-api-integration-password"),
                userCode,
                userCode.toLowerCase() + "@example.test");
    }

    private void assignRole(UUID userId, String roleCode) {
        jdbcTemplate.update("""
                INSERT INTO auth.user_role (
                    user_id,
                    role_id,
                    is_primary,
                    is_active
                )
                SELECT ?, role_id, TRUE, TRUE
                FROM auth.role
                WHERE role_code = ?
                """,
                userId,
                roleCode);
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

    private static AuditRequestContext requestContext() {
        return AuditRequestContext.requestIdOnly(
                UUID.randomUUID());
    }

    private static void assertBusinessError(
            Runnable action,
            ErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(expected));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            ReportCommandService.class,
            ReportQueryService.class,
            ReportQueryRepository.class,
            ReportResponseMapper.class,
            ReportAccessService.class,
            AuthenticationUserRepository.class,
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
            AuthenticationSessionConfiguration.class,
            PasswordHashingConfiguration.class
    })
    static class TestApplication {
    }
}
