package com.gilbom.robot.command;

public enum RobotCommandStatus {
    CREATED,
    SENT,
    ACCEPTED,
    COMPLETED,
    FAILED,
    REJECTED,
    EXPIRED,
    PUBLISH_FAILED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, REJECTED, EXPIRED, PUBLISH_FAILED -> true;
            case CREATED, SENT, ACCEPTED -> false;
        };
    }
}
