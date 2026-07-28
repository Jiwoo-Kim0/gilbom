package com.gilbom.auth.admin;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.AuthenticatedUserIdResolver;
import com.gilbom.auth.admin.AdminDtos.ApprovalRequest;
import com.gilbom.auth.admin.AdminDtos.MfaResetRequest;
import com.gilbom.auth.admin.AdminDtos.PageResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionOverridesRequest;
import com.gilbom.auth.admin.AdminDtos.PermissionResponse;
import com.gilbom.auth.admin.AdminDtos.ReasonRequest;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentResponse;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentsRequest;
import com.gilbom.auth.admin.AdminDtos.RoleCreateRequest;
import com.gilbom.auth.admin.AdminDtos.RolePermissionsRequest;
import com.gilbom.auth.admin.AdminDtos.RoleResponse;
import com.gilbom.auth.admin.AdminDtos.RoleUpdateRequest;
import com.gilbom.auth.admin.AdminDtos.UserResponse;
import com.gilbom.auth.admin.AdminDtos.UserRolesRequest;
import com.gilbom.auth.admin.AdminDtos.UserStatusRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAuthority('ADMIN_SETTINGS_MANAGE')")
public class AdminController {

    private final AdminManagementService managementService;
    private final AdminMfaResetService mfaResetService;
    private final AuthenticatedUserIdResolver userIdResolver;

    public AdminController(
            AdminManagementService managementService,
            AdminMfaResetService mfaResetService,
            AuthenticatedUserIdResolver userIdResolver
    ) {
        this.managementService = managementService;
        this.mfaResetService = mfaResetService;
        this.userIdResolver = userIdResolver;
    }

    @GetMapping("/registration-requests")
    public PageResponse<UserResponse> findRegistrationRequests(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int size
    ) {
        return managementService.findUsers(
                page,
                size,
                "PENDING_APPROVAL"
        );
    }

    @PostMapping("/registration-requests/{userId}/approve")
    public UserResponse approveRegistration(
            @PathVariable UUID userId,
            @Valid @RequestBody ApprovalRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.approveRegistration(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @PostMapping("/registration-requests/{userId}/reject")
    public ResponseEntity<Void> rejectRegistration(
            @PathVariable UUID userId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        managementService.rejectRegistration(
                userId,
                request.reason(),
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public PageResponse<UserResponse> findUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20")
            @Min(1) @Max(100) int size,
            @RequestParam(required = false) String accountStatus
    ) {
        return managementService.findUsers(
                page,
                size,
                accountStatus
        );
    }

    @GetMapping("/users/{userId}")
    public UserResponse findUser(@PathVariable UUID userId) {
        return managementService.findUser(userId);
    }

    @PatchMapping("/users/{userId}/status")
    public UserResponse changeUserStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UserStatusRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.changeUserStatus(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @PutMapping("/users/{userId}/roles")
    public UserResponse replaceUserRoles(
            @PathVariable UUID userId,
            @Valid @RequestBody UserRolesRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.replaceUserRoles(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @GetMapping("/users/{userId}/permission-overrides")
    public List<PermissionOverrideResponse> findPermissionOverrides(
            @PathVariable UUID userId
    ) {
        return managementService.findPermissionOverrides(userId);
    }

    @PutMapping("/users/{userId}/permission-overrides")
    public List<PermissionOverrideResponse> replacePermissionOverrides(
            @PathVariable UUID userId,
            @Valid @RequestBody PermissionOverridesRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.replacePermissionOverrides(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @GetMapping("/users/{userId}/robot-assignments")
    public List<RobotAssignmentResponse> findRobotAssignments(
            @PathVariable UUID userId
    ) {
        return managementService.findRobotAssignments(userId);
    }

    @PutMapping("/users/{userId}/robot-assignments")
    public List<RobotAssignmentResponse> replaceRobotAssignments(
            @PathVariable UUID userId,
            @Valid @RequestBody RobotAssignmentsRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.replaceRobotAssignments(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @PostMapping("/users/{userId}/mfa/reset")
    public ResponseEntity<Void> resetMfa(
            @PathVariable UUID userId,
            @Valid @RequestBody MfaResetRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        mfaResetService.reset(
                userId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    public List<RoleResponse> findRoles() {
        return managementService.findRoles();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(
            @Valid @RequestBody RoleCreateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        RoleResponse role = managementService.createRole(
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
        return ResponseEntity.created(
                URI.create("/api/v1/admin/roles/" + role.roleId())
        ).body(role);
    }

    @GetMapping("/roles/{roleId}")
    public RoleResponse findRole(@PathVariable UUID roleId) {
        return managementService.findRole(roleId);
    }

    @PatchMapping("/roles/{roleId}")
    public RoleResponse updateRole(
            @PathVariable UUID roleId,
            @Valid @RequestBody RoleUpdateRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.updateRole(
                roleId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> deactivateRole(
            @PathVariable UUID roleId,
            @Valid @RequestBody ReasonRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        managementService.deactivateRole(
                roleId,
                request.reason(),
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    public List<PermissionResponse> findPermissions() {
        return managementService.findPermissions();
    }

    @GetMapping("/roles/{roleId}/permissions")
    public List<PermissionResponse> findRolePermissions(
            @PathVariable UUID roleId
    ) {
        return managementService.findRolePermissions(roleId);
    }

    @PutMapping("/roles/{roleId}/permissions")
    public List<PermissionResponse> replaceRolePermissions(
            @PathVariable UUID roleId,
            @Valid @RequestBody RolePermissionsRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return managementService.replaceRolePermissions(
                roleId,
                request,
                actor(authentication),
                AuditRequestContext.from(servletRequest)
        );
    }

    private UUID actor(Authentication authentication) {
        return userIdResolver.require(authentication);
    }
}
