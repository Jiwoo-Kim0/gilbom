package com.gilbom.robot.command;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.robot.data.api.response.RobotCommandListResponse;
import com.gilbom.robot.data.api.response.RobotCommandResponse;
import com.gilbom.robot.data.api.response.RobotCommandResultListResponse;

@Service
public class RobotCommandQueryService {

    private final RobotCommandRepository repository;

    public RobotCommandQueryService(RobotCommandRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public RobotCommandListResponse findCommands(
            UUID userId,
            UUID robotId,
            int page,
            int size
    ) {
        if (!repository.existsVisibleRobot(userId, robotId)) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }
        long total = repository.countVisibleForRobot(userId, robotId);
        List<RobotCommandResponse> content = repository.findVisibleForRobot(
                        userId,
                        robotId,
                        size,
                        Math.multiplyExact((long) page, size))
                .stream()
                .map(RobotCommandQueryService::toResponse)
                .toList();
        int totalPages = Math.toIntExact(Math.ceilDiv(total, size));
        return new RobotCommandListResponse(
                content,
                page,
                size,
                total,
                totalPages);
    }

    @Transactional(readOnly = true)
    public RobotCommandResponse findCommand(UUID userId, UUID commandId) {
        return repository.findVisibleById(userId, commandId)
                .map(RobotCommandQueryService::toResponse)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.COMMAND_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public RobotCommandResultListResponse findResults(
            UUID userId,
            UUID commandId
    ) {
        if (repository.findVisibleById(userId, commandId).isEmpty()) {
            throw new BusinessException(ErrorCode.COMMAND_NOT_FOUND);
        }
        return new RobotCommandResultListResponse(
                repository.findVisibleResults(userId, commandId));
    }

    public static RobotCommandResponse toResponse(RobotCommandRecord command) {
        return new RobotCommandResponse(
                command.commandId(),
                command.robotId(),
                command.mqttId(),
                command.commandType(),
                command.commandStatus().name(),
                command.parameters(),
                command.requestedByType(),
                command.requestedByUserId(),
                command.requestedByUserCode(),
                command.issuedAt(),
                command.expiresAt(),
                command.sentAt(),
                command.acceptedAt(),
                command.completedAt(),
                command.publishAttemptCount(),
                command.lastErrorCode(),
                command.createdAt(),
                command.updatedAt());
    }
}
