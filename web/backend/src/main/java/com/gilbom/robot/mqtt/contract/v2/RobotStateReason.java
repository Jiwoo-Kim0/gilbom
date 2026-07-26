package com.gilbom.robot.mqtt.contract.v2;

public enum RobotStateReason {
    NONE,
    REMOTE_PAUSE,
    REMOTE_ESTOP,
    OBSTACLE,
    BOUNDARY_LOST,
    LOW_BATTERY,
    CRITICAL_BATTERY,
    LIDAR_FAULT,
    MOTOR_FAULT,
    INTERNAL_ERROR
}
