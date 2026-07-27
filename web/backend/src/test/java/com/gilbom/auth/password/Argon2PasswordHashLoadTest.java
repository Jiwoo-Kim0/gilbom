package com.gilbom.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("password-load")
class Argon2PasswordHashLoadTest {

    private static final String RAW_PASSWORD = "load test password";

    @Test
    void measuresHashingLatencyAndConcurrentVerificationResources() throws Exception {
        int encodeOperations = boundedProperty(
                "gilbom.password.load.encodes",
                4,
                1,
                20
        );
        int verifyOperations = boundedProperty(
                "gilbom.password.load.verifications",
                12,
                1,
                100
        );
        int concurrency = boundedProperty(
                "gilbom.password.load.concurrency",
                2,
                1,
                8
        );

        PasswordEncoder encoder =
                new PasswordHashingConfiguration().passwordEncoder();
        String warmupHash = encoder.encode(RAW_PASSWORD);
        assertThat(encoder.matches(RAW_PASSWORD, warmupHash)).isTrue();

        resetHeapPeaks();
        long cpuStarted = processCpuTime();
        long wallStarted = System.nanoTime();

        List<Long> encodeMillis = new ArrayList<>();
        String verificationHash = null;
        for (int index = 0; index < encodeOperations; index++) {
            long started = System.nanoTime();
            verificationHash = encoder.encode(RAW_PASSWORD);
            encodeMillis.add(elapsedMillis(started));
        }

        String immutableVerificationHash = verificationHash;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Long>> verificationFutures = new ArrayList<>();
        try {
            for (int index = 0; index < verifyOperations; index++) {
                verificationFutures.add(executor.submit(() -> {
                    startGate.await();
                    long started = System.nanoTime();
                    boolean matches =
                            encoder.matches(RAW_PASSWORD, immutableVerificationHash);
                    long elapsed = elapsedMillis(started);
                    if (!matches) {
                        throw new IllegalStateException(
                                "Argon2id verification unexpectedly failed");
                    }
                    return elapsed;
                }));
            }

            startGate.countDown();
            List<Long> verifyMillis = new ArrayList<>();
            for (Future<Long> future : verificationFutures) {
                verifyMillis.add(future.get(30, TimeUnit.SECONDS));
            }

            long wallMillis = elapsedMillis(wallStarted);
            long cpuMillis = nanosToMillis(processCpuTime() - cpuStarted);
            long peakHeapMib = bytesToMib(peakHeapBytes());
            long verifyP95 = percentile(verifyMillis, 0.95);

            System.out.printf(
                    "ARGON2_LOAD_RESULT memoryKiB=%d iterations=%d parallelism=%d "
                            + "concurrency=%d encodes=%d encodeP50Ms=%d encodeP95Ms=%d "
                            + "verifications=%d verifyP50Ms=%d verifyP95Ms=%d "
                            + "wallMs=%d processCpuMs=%d peakHeapApproxMiB=%d%n",
                    PasswordHashingConfiguration.MEMORY_KIB,
                    PasswordHashingConfiguration.ITERATIONS,
                    PasswordHashingConfiguration.PARALLELISM,
                    concurrency,
                    encodeOperations,
                    percentile(encodeMillis, 0.50),
                    percentile(encodeMillis, 0.95),
                    verifyOperations,
                    percentile(verifyMillis, 0.50),
                    verifyP95,
                    wallMillis,
                    cpuMillis,
                    peakHeapMib
            );

            assertThat(verifyMillis).hasSize(verifyOperations);
            assertThat(verifyP95).isLessThan(10_000);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static int boundedProperty(
            String name,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        int value = Integer.getInteger(name, defaultValue);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static long percentile(List<Long> values, double percentile) {
        List<Long> ordered = new ArrayList<>(values);
        Collections.sort(ordered);
        int index = (int) Math.ceil(percentile * ordered.size()) - 1;
        return ordered.get(Math.max(0, index));
    }

    private static long elapsedMillis(long startedNanos) {
        return nanosToMillis(System.nanoTime() - startedNanos);
    }

    private static long nanosToMillis(long nanoseconds) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanoseconds));
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    private static long peakHeapBytes() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP
                    && pool.getPeakUsage() != null) {
                total += Math.max(0, pool.getPeakUsage().getUsed());
            }
        }
        return total;
    }

    private static long bytesToMib(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static long processCpuTime() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getProcessCpuTime();
        }
        return 0;
    }
}
