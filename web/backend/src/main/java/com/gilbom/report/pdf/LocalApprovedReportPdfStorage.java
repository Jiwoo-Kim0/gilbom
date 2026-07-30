package com.gilbom.report.pdf;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Component
public class LocalApprovedReportPdfStorage
        implements ApprovedReportPdfStorage {

    private static final int MAXIMUM_PDF_BYTES = 10 * 1024 * 1024;

    private final Path storageRoot;

    public LocalApprovedReportPdfStorage(
            @Value("${gilbom.report.pdf.storage-root:"
                    + "${java.io.tmpdir}/gilbom-report-pdf}")
            String storageRoot
    ) {
        this.storageRoot = Path.of(storageRoot)
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public StoredPdf store(
            String storageKey,
            byte[] content,
            byte[] sha256
    ) {
        validate(content, sha256);
        Path target = resolve(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                return existing(target, storageKey, sha256);
            }

            temporary = target.getParent().resolve(
                    "." + target.getFileName()
                            + "." + UUID.randomUUID() + ".tmp");
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target);
                temporary = null;
                return new StoredPdf(storageKey, true);
            } catch (FileAlreadyExistsException exception) {
                return existing(target, storageKey, sha256);
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.S3_UNAVAILABLE);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 임시 파일 경로와 저장소 예외 원문은 로그에 남기지 않는다.
                }
            }
        }
    }

    @Override
    public void deleteCreated(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | RuntimeException ignored) {
            // transaction rollback 정리는 best effort다.
        }
    }

    private StoredPdf existing(
            Path target,
            String storageKey,
            byte[] expectedSha256
    ) throws IOException {
        byte[] existing = Files.readAllBytes(target);
        if (!MessageDigest.isEqual(
                sha256(existing),
                expectedSha256)) {
            throw new BusinessException(
                    ErrorCode.REPORT_STATE_CONFLICT,
                    "승인 PDF 저장 키가 이미 다른 파일에 결합돼 있습니다.");
        }
        return new StoredPdf(storageKey, false);
    }

    private Path resolve(String storageKey) {
        if (storageKey == null
                || storageKey.isBlank()
                || storageKey.startsWith("/")) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        Path target = storageRoot.resolve(storageKey).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
        return target;
    }

    private static void validate(
            byte[] content,
            byte[] sha256
    ) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (content.length < 8
                || content.length > MAXIMUM_PDF_BYTES
                || content[0] != '%'
                || content[1] != 'P'
                || content[2] != 'D'
                || content[3] != 'F'
                || sha256.length != 32
                || !MessageDigest.isEqual(
                        sha256(content),
                        sha256)) {
            throw new BusinessException(
                    ErrorCode.REPORT_VALIDATION_FAILED);
        }
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
