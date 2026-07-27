package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.common.web.RequestIdFilter;
import com.gilbom.robot.application.RobotRegistrationService;
import com.gilbom.robot.data.api.request.RobotRegistrationRequest;
import com.gilbom.robot.data.api.response.RobotRegistrationResponse;
import com.gilbom.robot.data.application.command.RegisterRobotCommand;
import com.gilbom.robot.data.application.result.RegisteredRobot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/robots")
public class RobotRegistrationController {

    private final RobotRegistrationService registrationService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotRegistrationController(
            RobotRegistrationService registrationService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.registrationService = registrationService;
        this.userIdResolver = userIdResolver;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROBOT_REGISTER')")
    public ResponseEntity<RobotRegistrationResponse> registerRobot(
            @Valid @RequestBody RobotRegistrationRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        UUID requestId = UUID.fromString(
                RequestIdFilter.getOrCreateRequestId(servletRequest));
        RegisteredRobot robot = registrationService.register(
                new RegisterRobotCommand(
                        request.robotName(),
                        request.serialNumber()),
                userIdResolver.require(authentication),
                idempotencyKey,
                requestId);

        RobotRegistrationResponse response = new RobotRegistrationResponse(
                robot.robotId(),
                robot.robotCode(),
                robot.robotName(),
                robot.serialNumber(),
                RobotRegistrationResponse.OperationalStatus.valueOf(
                        robot.operationalStatus()),
                RobotRegistrationResponse.ConnectivityStatus.valueOf(
                        robot.connectivityStatus()));

        return ResponseEntity
                .created(URI.create("/api/v1/robots/" + robot.robotId()))
                .body(response);
    }
}
