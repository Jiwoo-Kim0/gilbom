package com.gilbom.robot.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.robot.command.RobotCommandQueryService;
import com.gilbom.robot.command.RobotCommandService;
import com.gilbom.robot.data.api.request.RobotCommandCreateRequest;
import com.gilbom.robot.data.api.response.RobotCommandListResponse;
import com.gilbom.robot.data.api.response.RobotCommandResponse;
import com.gilbom.robot.data.api.response.RobotCommandResultListResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1")
public class RobotCommandController {

    private final RobotCommandService commandService;
    private final RobotCommandQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public RobotCommandController(
            RobotCommandService commandService,
            RobotCommandQueryService queryService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @PostMapping("/robots/{robotId}/commands")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_EXECUTE')")
    public ResponseEntity<RobotCommandResponse> createCommand(
            @PathVariable UUID robotId,
            @Valid @RequestBody RobotCommandCreateRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        RobotCommandResponse response = commandService.createAndPublish(
                robotId,
                request,
                userIdResolver.require(authentication),
                idempotencyKey,
                AuditRequestContext.from(servletRequest));
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/commands/" + response.commandId()))
                .body(response);
    }

    @GetMapping("/robots/{robotId}/commands")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_VIEW')")
    public RobotCommandListResponse findCommands(
            @PathVariable UUID robotId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication
    ) {
        return queryService.findCommands(
                userIdResolver.require(authentication),
                robotId,
                page,
                size);
    }

    @GetMapping("/commands/{commandId}")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_VIEW')")
    public RobotCommandResponse findCommand(
            @PathVariable UUID commandId,
            Authentication authentication
    ) {
        return queryService.findCommand(
                userIdResolver.require(authentication),
                commandId);
    }

    @GetMapping("/commands/{commandId}/results")
    @PreAuthorize("hasAuthority('ROBOT_COMMAND_VIEW')")
    public RobotCommandResultListResponse findResults(
            @PathVariable UUID commandId,
            Authentication authentication
    ) {
        return queryService.findResults(
                userIdResolver.require(authentication),
                commandId);
    }
}
