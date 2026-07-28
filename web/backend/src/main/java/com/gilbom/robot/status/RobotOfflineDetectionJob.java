package com.gilbom.robot.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RobotOfflineDetectionJob {

    private final RobotOfflineDetectionService detectionService;
    private final Clock clock;
    private final Duration offlineTimeout;

    public RobotOfflineDetectionJob(
            RobotOfflineDetectionService detectionService,
            Clock clock,
            @Value("${gilbom.robot.presence.offline-timeout:90s}")
            Duration offlineTimeout
    ) {
        this.detectionService = detectionService;
        this.clock = clock;
        this.offlineTimeout = offlineTimeout;
    }

    @Scheduled(
            fixedDelayString =
                    "${gilbom.robot.presence.offline-scan-interval:30s}"
    )
    public void detectOfflineRobots() {
        Instant detectedAt = clock.instant();
        detectionService.markTimedOut(
                detectedAt.minus(offlineTimeout),
                detectedAt);
    }
}
