package com.gilbom.auth.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gilbom.auth.password.validation.ValidPassword;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class AdminDtos {

    private AdminDtos() {
    }

    public enum AccountStatusChange {
        ACTIVE,
        DISABLED
    }

    public enum OverrideEffect {
        ALLOW,
        DENY
    }

    public enum RobotAssignmentType {
        VIEWER,
        OPERATOR,
        OWNER
    }

    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }

    public record UserResponse(
            UUID userId,
            String userCode,
            String email,
            String displayName,
            String departmentName,
            String jobTitle,
            String officeName,
            String accountStatus,
            boolean active,
            boolean mfaEnrolled,
            List<AssignedRoleResponse> roles,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AssignedRoleResponse(
            UUID roleId,
            String roleCode,
            String roleName,
            boolean primary
    ) {
    }

    public record RoleResponse(
            UUID roleId,
            String roleCode,
            String roleName,
            String description,
            boolean system,
            boolean active,
            int displayOrder,
            List<String> permissionCodes,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PermissionResponse(
            UUID permissionId,
            String permissionCode,
            String permissionName,
            String description,
            String resourceType,
            boolean system,
            boolean active,
            int displayOrder
    ) {
    }

    public record PermissionOverrideResponse(
            UUID permissionOverrideId,
            UUID permissionId,
            String permissionCode,
            OverrideEffect effect,
            String reason,
            Instant grantedAt,
            Instant expiresAt
    ) {
    }

    public record RobotAssignmentResponse(
            UUID assignmentId,
            UUID robotId,
            String robotName,
            RobotAssignmentType assignmentType,
            String reason,
            Instant assignedAt,
            Instant expiresAt
    ) {
    }

    public record ApprovalRequest(
            @NotEmpty
            @Size(max = 20)
            List<@NotNull UUID> roleIds,

            UUID primaryRoleId,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record ReasonRequest(
            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record UserStatusRequest(
            @NotNull
            AccountStatusChange status,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record UserRolesRequest(
            @NotNull
            @Size(max = 20)
            List<@NotNull UUID> roleIds,

            UUID primaryRoleId,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record PermissionOverridesRequest(
            @NotNull
            @Size(max = 100)
            List<@Valid PermissionOverrideItem> overrides,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record PermissionOverrideItem(
            @NotNull
            UUID permissionId,

            @NotNull
            OverrideEffect effect,

            Instant expiresAt,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record RobotAssignmentsRequest(
            @NotNull
            @Size(max = 500)
            List<@Valid RobotAssignmentItem> assignments,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record RobotAssignmentItem(
            @NotNull
            UUID robotId,

            @NotNull
            RobotAssignmentType assignmentType,

            Instant expiresAt,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record RoleCreateRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
            String roleCode,

            @NotBlank
            @Size(max = 100)
            String roleName,

            @NotBlank
            @Size(max = 500)
            String description,

            @PositiveOrZero
            @Max(10_000)
            int displayOrder,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record RoleUpdateRequest(
            @Size(min = 1, max = 100)
            String roleName,

            @Size(min = 1, max = 500)
            String description,

            Boolean active,

            @PositiveOrZero
            @Max(10_000)
            Integer displayOrder,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record RolePermissionsRequest(
            @NotNull
            @Size(max = 100)
            List<@NotNull UUID> permissionIds,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record MfaResetRequest(
            @ValidPassword
            String currentPassword,

            @NotBlank
            @Pattern(regexp = "^[0-9]{6}$")
            String totpCode,

            @NotBlank
            @Size(max = 500)
            String reason
    ) {
    }

    public record UserFilter(
            @Min(0)
            int page,

            @Min(1)
            @Max(100)
            int size,

            String accountStatus
    ) {
    }
}
