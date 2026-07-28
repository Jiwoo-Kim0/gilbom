package com.gilbom.robot.command;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RobotCommandTimeoutJob {

    private final RobotCommandTimeoutService timeoutService;
    private final Clock clock;

    public RobotCommandTimeoutJob(
            RobotCommandTimeoutService timeoutService,
            Clock clock
    ) {
        this.timeoutService = timeoutService;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${gilbom.robot.command.expiry-scan-interval:5s}"
    )
    public void expireCommands() {
        timeoutService.expireCommands(clock.instant());
    }
}
