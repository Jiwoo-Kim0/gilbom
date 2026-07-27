package com.gilbom.robot.data.application.command;

public record RegisterRobotCommand(
        String robotName,
        String serialNumber
) {
}
