package com.gilbom.robot.mission;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MissionAutoCompletionJob {

    private final MissionCompletionCommandDispatcher commandDispatcher;
    private final Clock clock;

    public MissionAutoCompletionJob(
            MissionCompletionCommandDispatcher commandDispatcher,
            Clock clock
    ) {
        this.commandDispatcher = commandDispatcher;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${gilbom.robot.mission.completion-scan-interval:5s}"
    )
    public void completeDueMissions() {
        commandDispatcher.dispatchDueMissions(clock.instant());
    }
}
