package com.gilbom.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

class StorageReadinessHealthIndicatorTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void reportsUpAfterWritingAndRemovingBothProbes()
            throws IOException {
        Path reportRoot = temporaryDirectory.resolve("pdf");
        Path imageRoot = temporaryDirectory.resolve("images");
        StorageReadinessHealthIndicator indicator =
                new StorageReadinessHealthIndicator(
                        reportRoot,
                        imageRoot);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("writableTargets", 2);
        try (Stream<Path> reportEntries = Files.list(reportRoot);
                Stream<Path> imageEntries = Files.list(imageRoot)) {
            assertThat(reportEntries).isEmpty();
            assertThat(imageEntries).isEmpty();
        }
    }

    @Test
    void reportsDownWithoutExposingPathWhenAStorageRootIsNotWritable()
            throws IOException {
        Path reportRoot = temporaryDirectory.resolve("pdf");
        Path imageRoot = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(imageRoot, "occupied");
        StorageReadinessHealthIndicator indicator =
                new StorageReadinessHealthIndicator(
                        reportRoot,
                        imageRoot);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsOnlyKeys("failedTarget")
                .containsEntry("failedTarget", "defectImage");
        assertThat(health.getDetails().toString())
                .doesNotContain(temporaryDirectory.toString());
    }
}
