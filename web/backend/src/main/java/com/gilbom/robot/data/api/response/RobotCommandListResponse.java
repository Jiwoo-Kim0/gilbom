package com.gilbom.robot.data.api.response;

import java.util.List;

public record RobotCommandListResponse(
        List<RobotCommandResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public RobotCommandListResponse {
        content = List.copyOf(content);
    }
}
