package com.gilbom.robot.data.api.response;

import java.util.List;

public record MqttCredentialListResponse(
        List<MqttCredentialResponse> credentials
) {

    public MqttCredentialListResponse {
        credentials = List.copyOf(credentials);
    }
}
