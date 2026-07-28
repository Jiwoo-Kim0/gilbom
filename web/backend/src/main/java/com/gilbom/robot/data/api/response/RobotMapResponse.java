package com.gilbom.robot.data.api.response;

import java.util.List;

public record RobotMapResponse(
        List<RobotMapMarkerResponse> robots
) {

    public RobotMapResponse {
        robots = List.copyOf(robots);
    }
}
