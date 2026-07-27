package com.gilbom.robot.data.mqtt.v2.common;

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
