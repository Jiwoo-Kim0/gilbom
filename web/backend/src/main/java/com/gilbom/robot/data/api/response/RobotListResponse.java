package com.gilbom.robot.data.api.response;

import java.util.List;

public record RobotListResponse(
        List<RobotListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public RobotListResponse {
        content = List.copyOf(content);
    }
}
