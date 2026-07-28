package com.gilbom.robot.data.api.response;

public record RobotSummaryResponse(
        long totalRobots,
        long onlineRobots,
        long offlineRobots,
        long warningRobots,
        long criticalRobots
) {
}
