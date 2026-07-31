package com.gilbom.health;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.gilbom.storage.StorageProperties;

/**
 * 영속 저장소 mount가 실제 runtime 사용자에게 쓰기 가능한지 확인한다.
 *
 * <p>권한 비트만 확인하면 read-only mount, ACL, 디스크 오류를 놓칠 수 있으므로 각
 * 저장소 최상위 경로에 작은 임시 파일을 만들고 삭제한다. 경로와 예외 원문은 health
 * 응답에 노출하지 않는다.
 */
@Component("storageReadiness")
public final class StorageReadinessHealthIndicator
        implements HealthIndicator {

    private static final byte[] PROBE_CONTENT = {0x47};

    private final Path reportPdfRoot;
    private final Path defectImageRoot;

    @Autowired
    public StorageReadinessHealthIndicator(
            StorageProperties storageProperties,
            @Value("${gilbom.report.pdf.storage-root:"
                    + "${java.io.tmpdir}/gilbom-report-pdf}")
            String reportPdfRoot
    ) {
        this(
                Path.of(reportPdfRoot),
                Path.of(storageProperties.getLocalRootDirectory()));
    }

    StorageReadinessHealthIndicator(
            Path reportPdfRoot,
            Path defectImageRoot
    ) {
        this.reportPdfRoot = normalize(reportPdfRoot);
        this.defectImageRoot = normalize(defectImageRoot);
    }

    @Override
    public Health health() {
        String target = "reportPdf";
        try {
            probe(reportPdfRoot);
            target = "defectImage";
            probe(defectImageRoot);
            return Health.up()
                    .withDetail("writableTargets", 2)
                    .build();
        } catch (IOException | SecurityException exception) {
            return Health.down()
                    .withDetail("failedTarget", target)
                    .build();
        }
    }

    private static void probe(Path root) throws IOException {
        Files.createDirectories(root);
        Path probe = Files.createTempFile(
                root,
                ".gilbom-readiness-",
                ".probe");
        boolean deleted = false;
        try {
            Files.write(
                    probe,
                    PROBE_CONTENT,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.delete(probe);
            deleted = true;
        } finally {
            if (!deleted) {
                Files.deleteIfExists(probe);
            }
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
