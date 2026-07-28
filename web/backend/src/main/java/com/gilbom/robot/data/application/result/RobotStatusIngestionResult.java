package com.gilbom.robot.data.application.result;

public record RobotStatusIngestionResult(
        boolean historyInserted,
        boolean currentStateUpdated
) {
}

