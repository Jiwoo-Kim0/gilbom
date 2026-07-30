package com.gilbom.report.pdf;

public interface ApprovedReportPdfStorage {

    StoredPdf store(
            String storageKey,
            byte[] content,
            byte[] sha256);

    void deleteCreated(String storageKey);

    record StoredPdf(
            String storageKey,
            boolean created
    ) {
    }
}
