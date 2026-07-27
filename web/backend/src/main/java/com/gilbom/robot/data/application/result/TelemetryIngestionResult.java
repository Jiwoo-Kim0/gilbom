package com.gilbom.robot.data.application.result;

public record TelemetryIngestionResult(
        boolean historyInserted,
        boolean currentStateUpdated
) {
}
