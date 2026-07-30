package com.gilbom.report.pdf;

import java.time.Instant;

public record ApprovedReportPdfArtifact(
        String storageKey,
        byte[] sha256,
        Instant createdAt
) {

    public ApprovedReportPdfArtifact {
        sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
        return sha256.clone();
    }
}
