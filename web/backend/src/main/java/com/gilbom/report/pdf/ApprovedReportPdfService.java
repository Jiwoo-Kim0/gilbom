package com.gilbom.report.pdf;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gilbom.report.ReportRecord;
import com.gilbom.report.pdf.ApprovedReportPdfStorage.StoredPdf;

@Service
public class ApprovedReportPdfService {

    private final ApprovedReportPdfRenderer renderer;
    private final ApprovedReportPdfStorage storage;

    public ApprovedReportPdfService(
            ApprovedReportPdfRenderer renderer,
            ApprovedReportPdfStorage storage
    ) {
        this.renderer = renderer;
        this.storage = storage;
    }

    public ApprovedReportPdfArtifact create(
            ReportRecord report,
            UUID approvedBy,
            Instant approvedAt
    ) {
        Objects.requireNonNull(report, "report must not be null");
        byte[] pdf = renderer.render(
                report,
                approvedBy,
                approvedAt);
        byte[] sha256 = sha256(pdf);
        String storageKey = "reports/"
                + report.reportId()
                + "/v" + report.currentVersionNo()
                + "/" + HexFormat.of().formatHex(sha256)
                + ".pdf";
        StoredPdf stored = storage.store(
                storageKey,
                pdf,
                sha256);
        registerRollbackCleanup(stored);
        return new ApprovedReportPdfArtifact(
                stored.storageKey(),
                sha256,
                approvedAt);
    }

    private void registerRollbackCleanup(StoredPdf stored) {
        if (!stored.created()
                || !TransactionSynchronizationManager
                        .isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            storage.deleteCreated(
                                    stored.storageKey());
                        }
                    }
                });
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the JVM",
                    exception);
        }
    }
}
