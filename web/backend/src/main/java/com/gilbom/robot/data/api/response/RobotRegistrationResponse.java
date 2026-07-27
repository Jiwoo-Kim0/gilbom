package com.gilbom.robot.data.api.response;

import java.util.UUID;

public record RobotRegistrationResponse(
        UUID robotId,
        String robotCode,
        String robotName,
        String serialNumber,
        OperationalStatus operationalStatus,
        ConnectivityStatus connectivityStatus
) {

    public enum OperationalStatus {
        PROVISIONING
    }

    public enum ConnectivityStatus {
        OFFLINE
    }
}
