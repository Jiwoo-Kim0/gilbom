package com.gilbom.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportGenerationMethod;
import com.gilbom.report.ReportRecord;
import com.gilbom.report.ReportType;

class ApprovedReportPdfTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void rendersKoreanReportAsNonExecutablePdfText()
            throws Exception {
        UUID reportId = UUID.randomUUID();
        ReportRecord report = mock(ReportRecord.class);
        when(report.reportId()).thenReturn(reportId);
        when(report.reportCode()).thenReturn("RPT-PDF-001");
        when(report.currentVersionNo()).thenReturn(2);
        when(report.periodFrom()).thenReturn(
                Instant.parse("2026-07-01T00:00:00Z"));
        when(report.periodTo()).thenReturn(
                Instant.parse("2026-07-07T23:59:59Z"));
        when(report.reportType()).thenReturn(ReportType.PERIODIC);
        when(report.currentVersionGenerationMethod())
                .thenReturn(ReportGenerationMethod.MANUAL);
        when(report.title()).thenReturn("길봄 승인 보고서");
        when(report.summary()).thenReturn("안전한 승인 요약");
        when(report.statistics()).thenReturn(
                Map.of("defectCount", 2));
        when(report.evidenceSnapshot()).thenReturn(
                Map.of(
                        "defectIds", java.util.List.of(),
                        "missionIds", java.util.List.of(),
                        "patrolAreaIds", java.util.List.of()));
        when(report.body()).thenReturn(
                "사용자 문자열 ) Tj /JavaScript 를 명령으로 실행하지 않는다.");

        byte[] pdf = new KoreanApprovedReportPdfRenderer(
                new ObjectMapper()).render(
                        report,
                        UUID.randomUUID(),
                        Instant.parse("2026-07-30T09:00:00Z"));
        String pdfSyntax = new String(
                pdf,
                StandardCharsets.ISO_8859_1);

        assertThat(pdf).startsWith(
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdfSyntax)
                .contains("/UniKS-UCS2-H")
                .contains("AE38")
                .contains("%%EOF")
                .doesNotContain("/JavaScript");
    }

    @Test
    void localStorageReusesSameBytesAndRejectsReplacement()
            throws Exception {
        LocalApprovedReportPdfStorage storage =
                new LocalApprovedReportPdfStorage(
                        temporaryDirectory.toString());
        String storageKey = "reports/"
                + UUID.randomUUID() + "/v1/approved.pdf";
        byte[] pdf = "%PDF-1.7\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] sha256 = sha256(pdf);

        ApprovedReportPdfStorage.StoredPdf first =
                storage.store(storageKey, pdf, sha256);
        ApprovedReportPdfStorage.StoredPdf replay =
                storage.store(storageKey, pdf, sha256);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(Files.readAllBytes(
                temporaryDirectory.resolve(storageKey)))
                .containsExactly(pdf);

        byte[] replacement = "%PDF-1.7\nreplacement\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> storage.store(
                storageKey,
                replacement,
                sha256(replacement)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.REPORT_STATE_CONFLICT));
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
