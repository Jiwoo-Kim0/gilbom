package com.gilbom.robot.data.api.response;

import java.util.List;

public record RobotCommandResultListResponse(
        List<RobotCommandResultResponse> results
) {

    public RobotCommandResultListResponse {
        results = List.copyOf(results);
    }
}
