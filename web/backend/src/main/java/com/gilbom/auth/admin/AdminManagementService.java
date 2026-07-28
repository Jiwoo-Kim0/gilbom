package com.gilbom.auth.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditSnapshot;
import com.gilbom.audit.AuditTargetType;
import com.gilbom.auth.admin.AdminDtos.ApprovalRequest;
import com.gilbom.auth.admin.AdminDtos.PageResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideItem;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideResponse;
import com.gilbom.auth.admin.AdminDtos.PermissionOverridesRequest;
import com.gilbom.auth.admin.AdminDtos.PermissionResponse;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentItem;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentResponse;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentsRequest;
import com.gilbom.auth.admin.AdminDtos.RoleCreateRequest;
import com.gilbom.auth.admin.AdminDtos.RolePermissionsRequest;
import com.gilbom.auth.admin.AdminDtos.RoleResponse;
import com.gilbom.auth.admin.AdminDtos.RoleUpdateRequest;
import com.gilbom.auth.admin.AdminDtos.UserResponse;
import com.gilbom.auth.admin.AdminDtos.UserRolesRequest;
import com.gilbom.auth.admin.AdminDtos.UserStatusRequest;
import com.gilbom.auth.admin.AdminRepository.PermissionRef;
import com.gilbom.auth.admin.AdminRepository.RoleRef;
import com.gilbom.auth.admin.AdminRepository.RoleState;
import com.gilbom.auth.admin.AdminRepository.UserState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class AdminManagementService {

    private static final String ADMIN_SETTINGS_MANAGE =
            "ADMIN_SETTINGS_MANAGE";
    private static final Set<String> ACCOUNT_STATUSES = Set.of(
            "PENDING_APPROVAL",
            "ACTIVE",
            "DISABLED",
            "REJECTED",
            "DELETED"
    );

    private final AdminRepository repository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminManagementService(
            AdminRepository repository,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> findUsers(
            int page,
            int size,
            String accountStatus
    ) {
        String normalizedStatus = normalizeOptionalStatus(accountStatus);
        long total = repository.countUsers(normalizedStatus);
        return new PageResponse<>(
                repository.findUsers(page, size, normalizedStatus),
                page,
                size,
                total,
                total == 0 ? 0 : (int) ((total + size - 1) / size)
        );
    }

    @Transactional(readOnly = true)
    public UserResponse findUser(UUID userId) {
        return repository.findUser(userId).orElseThrow(
                AdminManagementService::userNotFound
        );
    }

    @Transactional
    public UserResponse approveRegistration(
            UUID userId,
            ApprovalRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        List<UUID> roleIds = distinctIds(request.roleIds(), "roleIds");
        UUID primaryRoleId = resolvePrimaryRole(
                roleIds,
                request.primaryRoleId()
        );

        repository.lockLastAdminGuard();
        UserState before = requireUserForUpdate(userId);
        if (!"PENDING_APPROVAL".equals(before.accountStatus())) {
            throw conflict("승인 대기 중인 가입 요청이 아닙니다.");
        }
        requireActiveRoles(roleIds);

        repository.replaceUserRoles(
                userId,
                roleIds,
                primaryRoleId,
                actorUserId
        );
        if (repository.approvePendingUser(userId) != 1) {
            throw conflict("가입 요청 상태가 변경되어 승인할 수 없습니다.");
        }

        appendUserAudit(
                "ADMIN_REGISTRATION_APPROVED",
                userId,
                actorUserId,
                context,
                snapshot(Map.of(
                        "accountStatus", before.accountStatus(),
                        "roleIds", List.of()
                )),
                snapshot(Map.of(
                        "accountStatus", "ACTIVE",
                        "roleIds", roleIds,
                        "primaryRoleId", primaryRoleId
                )),
                request.reason()
        );
        return findUser(userId);
    }

    @Transactional
    public void rejectRegistration(
            UUID userId,
            String reason,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        UserState before = requireUserForUpdate(userId);
        if (!"PENDING_APPROVAL".equals(before.accountStatus())) {
            throw conflict("승인 대기 중인 가입 요청이 아닙니다.");
        }
        if (repository.rejectPendingUser(userId) != 1) {
            throw conflict("가입 요청 상태가 변경되어 거절할 수 없습니다.");
        }
        appendUserAudit(
                "ADMIN_REGISTRATION_REJECTED",
                userId,
                actorUserId,
                context,
                snapshot(Map.of("accountStatus", before.accountStatus())),
                snapshot(Map.of("accountStatus", "REJECTED")),
                reason
        );
    }

    @Transactional
    public UserResponse changeUserStatus(
            UUID userId,
            UserStatusRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        UserState before = requireUserForUpdate(userId);
        String afterStatus = request.status().name();
        if (!Set.of("ACTIVE", "DISABLED").contains(
                before.accountStatus()
        )) {
            throw conflict(
                    "활성 또는 비활성 계정만 상태를 변경할 수 있습니다."
            );
        }
        if (before.accountStatus().equals(afterStatus)) {
            throw conflict("이미 요청한 계정 상태입니다.");
        }
        if ("DISABLED".equals(afterStatus)) {
            requireAdminReductionAllowed(userId);
        }
        if (repository.changeUserStatus(userId, afterStatus) != 1) {
            throw conflict("계정 상태가 변경되어 처리할 수 없습니다.");
        }
        appendUserAudit(
                "ADMIN_USER_STATUS_CHANGED",
                userId,
                actorUserId,
                context,
                snapshot(Map.of(
                        "accountStatus", before.accountStatus(),
                        "active", before.active()
                )),
                snapshot(Map.of(
                        "accountStatus", afterStatus,
                        "active", "ACTIVE".equals(afterStatus)
                )),
                request.reason()
        );
        return findUser(userId);
    }

    @Transactional
    public UserResponse replaceUserRoles(
            UUID userId,
            UserRolesRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        List<UUID> roleIds = distinctIds(request.roleIds(), "roleIds");
        UUID primaryRoleId = resolvePrimaryRole(
                roleIds,
                request.primaryRoleId()
        );

        repository.lockLastAdminGuard();
        UserState user = requireUserForUpdate(userId);
        requireManagedUser(user);
        List<RoleRef> roles = requireActiveRoles(roleIds);
        boolean keepsSystemAdmin = roles.stream().anyMatch(
                role -> role.system() && "ADMIN".equals(role.roleCode())
        );
        if (!keepsSystemAdmin) {
            requireAdminReductionAllowed(userId);
        }

        List<UUID> beforeRoleIds = repository.findUser(userId)
                .orElseThrow(AdminManagementService::userNotFound)
                .roles()
                .stream()
                .map(AdminDtos.AssignedRoleResponse::roleId)
                .toList();
        repository.replaceUserRoles(
                userId,
                roleIds,
                primaryRoleId,
                actorUserId
        );
        appendUserAudit(
                "RBAC_USER_ROLES_CHANGED",
                userId,
                actorUserId,
                context,
                snapshot(Map.of("roleIds", beforeRoleIds)),
                snapshot(Map.of(
                        "roleIds", roleIds,
                        "primaryRoleId", nullable(primaryRoleId)
                )),
                request.reason()
        );
        return findUser(userId);
    }

    @Transactional(readOnly = true)
    public List<PermissionOverrideResponse> findPermissionOverrides(
            UUID userId
    ) {
        requireUser(userId);
        return repository.findPermissionOverrides(userId);
    }

    @Transactional
    public List<PermissionOverrideResponse> replacePermissionOverrides(
            UUID userId,
            PermissionOverridesRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        validateOverrideItems(request.overrides());
        repository.lockLastAdminGuard();
        UserState user = requireUserForUpdate(userId);
        requireManagedUser(user);
        List<PermissionRef> permissions = requireActivePermissions(
                request.overrides().stream()
                        .map(PermissionOverrideItem::permissionId)
                        .toList()
        );
        Map<UUID, String> codesById = permissions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PermissionRef::permissionId,
                        PermissionRef::permissionCode
                ));
        boolean prohibitedAllow = request.overrides().stream().anyMatch(
                override ->
                        override.effect()
                                == AdminDtos.OverrideEffect.ALLOW
                        && ADMIN_SETTINGS_MANAGE.equals(
                                codesById.get(override.permissionId())
                        )
        );
        if (prohibitedAllow) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "시스템 관리자 권한은 사용자 예외로 부여할 수 없습니다."
            );
        }
        boolean deniesAdminSettings = request.overrides().stream().anyMatch(
                override ->
                        override.effect()
                                == AdminDtos.OverrideEffect.DENY
                        && ADMIN_SETTINGS_MANAGE.equals(
                                codesById.get(override.permissionId())
                        )
        );
        if (deniesAdminSettings) {
            requireAdminReductionAllowed(userId);
        }

        List<PermissionOverrideResponse> before =
                repository.findPermissionOverrides(userId);
        repository.replacePermissionOverrides(
                userId,
                request.overrides(),
                actorUserId,
                normalizeReason(request.reason())
        );
        List<PermissionOverrideResponse> after =
                repository.findPermissionOverrides(userId);
        appendUserAudit(
                "RBAC_USER_OVERRIDES_CHANGED",
                userId,
                actorUserId,
                context,
                overrideSnapshot(before),
                overrideSnapshot(after),
                request.reason()
        );
        return after;
    }

    @Transactional(readOnly = true)
    public List<RobotAssignmentResponse> findRobotAssignments(
            UUID userId
    ) {
        requireUser(userId);
        return repository.findRobotAssignments(userId);
    }

    @Transactional
    public List<RobotAssignmentResponse> replaceRobotAssignments(
            UUID userId,
            RobotAssignmentsRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        validateAssignmentItems(request.assignments());
        repository.lockLastAdminGuard();
        UserState user = requireUserForUpdate(userId);
        requireManagedUser(user);
        List<UUID> robotIds = request.assignments().stream()
                .map(RobotAssignmentItem::robotId)
                .toList();
        if (repository.countActiveRobots(robotIds) != robotIds.size()) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_FOUND);
        }

        List<RobotAssignmentResponse> before =
                repository.findRobotAssignments(userId);
        repository.replaceRobotAssignments(
                userId,
                request.assignments(),
                actorUserId,
                normalizeReason(request.reason())
        );
        List<RobotAssignmentResponse> after =
                repository.findRobotAssignments(userId);
        appendUserAudit(
                "RBAC_USER_ROBOT_ASSIGNMENTS_CHANGED",
                userId,
                actorUserId,
                context,
                assignmentSnapshot(before),
                assignmentSnapshot(after),
                request.reason()
        );
        return after;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> findRoles() {
        return repository.findRoles();
    }

    @Transactional(readOnly = true)
    public RoleResponse findRole(UUID roleId) {
        return repository.findRole(roleId).orElseThrow(
                AdminManagementService::roleNotFound
        );
    }

    @Transactional
    public RoleResponse createRole(
            RoleCreateRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        UUID roleId;
        try {
            roleId = repository.insertRole(
                    request.roleCode(),
                    request.roleName().trim(),
                    request.description().trim(),
                    request.displayOrder(),
                    actorUserId
            );
        } catch (DataIntegrityViolationException exception) {
            throw conflict("이미 존재하거나 사용할 수 없는 역할 코드입니다.");
        }
        appendRoleAudit(
                "RBAC_ROLE_CREATED",
                roleId,
                actorUserId,
                context,
                null,
                snapshot(Map.of(
                        "roleCode", request.roleCode(),
                        "roleName", request.roleName().trim(),
                        "active", true,
                        "displayOrder", request.displayOrder()
                )),
                request.reason()
        );
        return findRole(roleId);
    }

    @Transactional
    public RoleResponse updateRole(
            UUID roleId,
            RoleUpdateRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        RoleState before = requireRoleForUpdate(roleId);
        if (request.roleName() == null
                && request.description() == null
                && request.active() == null
                && request.displayOrder() == null) {
            throw invalid("변경할 역할 필드가 하나 이상 필요합니다.");
        }
        if (before.system()
                && Boolean.FALSE.equals(request.active())) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "시스템 역할은 비활성화할 수 없습니다."
            );
        }
        String roleName = trimNullable(request.roleName());
        String description = trimNullable(request.description());
        repository.updateRole(
                roleId,
                roleName,
                description,
                request.active(),
                request.displayOrder()
        );
        RoleResponse after = findRole(roleId);
        appendRoleAudit(
                "RBAC_ROLE_CHANGED",
                roleId,
                actorUserId,
                context,
                roleStateSnapshot(before),
                roleSnapshot(after),
                request.reason()
        );
        return after;
    }

    @Transactional
    public void deactivateRole(
            UUID roleId,
            String reason,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        repository.lockLastAdminGuard();
        RoleState before = requireRoleForUpdate(roleId);
        if (before.system()) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "시스템 역할은 비활성화할 수 없습니다."
            );
        }
        if (!before.active()) {
            throw conflict("이미 비활성화된 역할입니다.");
        }
        if (repository.deactivateRole(roleId) != 1) {
            throw conflict("역할 상태가 변경되어 처리할 수 없습니다.");
        }
        appendRoleAudit(
                "RBAC_ROLE_DEACTIVATED",
                roleId,
                actorUserId,
                context,
                roleStateSnapshot(before),
                snapshot(Map.of(
                        "roleCode", before.roleCode(),
                        "active", false
                )),
                reason
        );
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> findPermissions() {
        return repository.findPermissions();
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> findRolePermissions(UUID roleId) {
        RoleResponse role = findRole(roleId);
        Set<String> codes = Set.copyOf(role.permissionCodes());
        return repository.findPermissions().stream()
                .filter(permission ->
                        codes.contains(permission.permissionCode()))
                .toList();
    }

    @Transactional
    public List<PermissionResponse> replaceRolePermissions(
            UUID roleId,
            RolePermissionsRequest request,
            UUID actorUserId,
            AuditRequestContext context
    ) {
        List<UUID> permissionIds = distinctIds(
                request.permissionIds(),
                "permissionIds"
        );
        repository.lockLastAdminGuard();
        RoleState role = requireRoleForUpdate(roleId);
        List<PermissionRef> permissions = requireActivePermissions(
                permissionIds
        );
        boolean includesAdminSettings = permissions.stream().anyMatch(
                permission -> ADMIN_SETTINGS_MANAGE.equals(
                        permission.permissionCode()
                )
        );
        if (includesAdminSettings
                && (!role.system() || !"ADMIN".equals(role.roleCode()))) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "시스템 관리자 역할에만 관리 권한을 부여할 수 있습니다."
            );
        }
        if (role.system()
                && "ADMIN".equals(role.roleCode())
                && !includesAdminSettings) {
            throw new BusinessException(
                    ErrorCode.AUTH_LAST_ADMIN_PROTECTED,
                    "시스템 관리자 역할의 관리 권한은 제거할 수 없습니다."
            );
        }

        List<UUID> beforeIds =
                repository.findActiveRolePermissionIds(roleId);
        try {
            repository.replaceRolePermissions(
                    roleId,
                    permissionIds,
                    actorUserId
            );
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        appendRoleAudit(
                "RBAC_ROLE_PERMISSIONS_CHANGED",
                roleId,
                actorUserId,
                context,
                snapshot(Map.of("permissionIds", beforeIds)),
                snapshot(Map.of("permissionIds", permissionIds)),
                request.reason()
        );
        return findRolePermissions(roleId);
    }

    private void requireAdminReductionAllowed(UUID userId) {
        if (repository.isActiveSystemAdmin(userId)
                && repository.countActiveSystemAdmins() <= 1) {
            throw new BusinessException(
                    ErrorCode.AUTH_LAST_ADMIN_PROTECTED
            );
        }
    }

    private UserState requireUserForUpdate(UUID userId) {
        return repository.findUserForUpdate(userId).orElseThrow(
                AdminManagementService::userNotFound
        );
    }

    private void requireUser(UUID userId) {
        if (repository.findUser(userId).isEmpty()) {
            throw userNotFound();
        }
    }

    private RoleState requireRoleForUpdate(UUID roleId) {
        return repository.findRoleForUpdate(roleId).orElseThrow(
                AdminManagementService::roleNotFound
        );
    }

    private List<RoleRef> requireActiveRoles(List<UUID> roleIds) {
        List<RoleRef> roles = repository.findActiveRoleRefs(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new BusinessException(ErrorCode.AUTH_ROLE_NOT_FOUND);
        }
        return roles;
    }

    private List<PermissionRef> requireActivePermissions(
            List<UUID> rawPermissionIds
    ) {
        List<UUID> permissionIds = distinctIds(
                rawPermissionIds,
                "permissionIds"
        );
        List<PermissionRef> permissions =
                repository.findActivePermissionRefs(permissionIds);
        if (permissions.size() != permissionIds.size()) {
            throw new BusinessException(
                    ErrorCode.AUTH_PERMISSION_NOT_FOUND
            );
        }
        return permissions;
    }

    private void requireManagedUser(UserState user) {
        if (Set.of("PENDING_APPROVAL", "REJECTED", "DELETED")
                .contains(user.accountStatus())) {
            throw conflict(
                    "승인된 활성 또는 비활성 계정만 관리할 수 있습니다."
            );
        }
    }

    private void validateOverrideItems(
            List<PermissionOverrideItem> overrides
    ) {
        Set<String> keys = new HashSet<>();
        Instant now = clock.instant();
        for (PermissionOverrideItem override : overrides) {
            String key = override.permissionId()
                    + ":" + override.effect().name();
            if (!keys.add(key)) {
                throw invalid("동일한 권한과 효과를 중복 지정할 수 없습니다.");
            }
            requireFutureExpiry(override.expiresAt(), now);
        }
    }

    private void validateAssignmentItems(
            List<RobotAssignmentItem> assignments
    ) {
        Set<UUID> robotIds = new HashSet<>();
        Instant now = clock.instant();
        for (RobotAssignmentItem assignment : assignments) {
            if (!robotIds.add(assignment.robotId())) {
                throw invalid("같은 로봇을 중복 배정할 수 없습니다.");
            }
            requireFutureExpiry(assignment.expiresAt(), now);
        }
    }

    private static void requireFutureExpiry(
            Instant expiresAt,
            Instant now
    ) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw invalid("만료 시각은 현재보다 미래여야 합니다.");
        }
    }

    private static List<UUID> distinctIds(
            List<UUID> ids,
            String field
    ) {
        List<UUID> distinct = ids.stream().distinct().toList();
        if (distinct.size() != ids.size()) {
            throw invalid(field + "에 중복 값이 있습니다.");
        }
        return distinct;
    }

    private static UUID resolvePrimaryRole(
            List<UUID> roleIds,
            UUID requestedPrimary
    ) {
        if (roleIds.isEmpty()) {
            if (requestedPrimary != null) {
                throw invalid(
                        "역할이 없으면 대표 역할을 지정할 수 없습니다."
                );
            }
            return null;
        }
        UUID primary = requestedPrimary == null
                ? roleIds.getFirst()
                : requestedPrimary;
        if (!roleIds.contains(primary)) {
            throw invalid("대표 역할은 역할 목록에 포함되어야 합니다.");
        }
        return primary;
    }

    private static String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(
                java.util.Locale.ROOT
        );
        if (!ACCOUNT_STATUSES.contains(normalized)) {
            throw invalid("지원하지 않는 계정 상태입니다.");
        }
        return normalized;
    }

    private void appendUserAudit(
            String action,
            UUID targetId,
            UUID actorId,
            AuditRequestContext context,
            AuditSnapshot before,
            AuditSnapshot after,
            String reason
    ) {
        appendAudit(
                action,
                AuditTargetType.USER,
                targetId,
                actorId,
                context,
                before,
                after,
                reason
        );
    }

    private void appendRoleAudit(
            String action,
            UUID targetId,
            UUID actorId,
            AuditRequestContext context,
            AuditSnapshot before,
            AuditSnapshot after,
            String reason
    ) {
        appendAudit(
                action,
                AuditTargetType.ROLE,
                targetId,
                actorId,
                context,
                before,
                after,
                reason
        );
    }

    private void appendAudit(
            String action,
            AuditTargetType targetType,
            UUID targetId,
            UUID actorId,
            AuditRequestContext context,
            AuditSnapshot before,
            AuditSnapshot after,
            String reason
    ) {
        auditLogService.appendRequired(new AuditLogRecord(
                context,
                actorId,
                AuditActorType.USER,
                action,
                targetType,
                targetId,
                before,
                after,
                normalizeReason(reason),
                AuditResult.SUCCESS
        ));
    }

    private static AuditSnapshot roleStateSnapshot(RoleState role) {
        return snapshot(Map.of(
                "roleCode", role.roleCode(),
                "roleName", role.roleName(),
                "active", role.active(),
                "displayOrder", role.displayOrder()
        ));
    }

    private static AuditSnapshot roleSnapshot(RoleResponse role) {
        return snapshot(Map.of(
                "roleCode", role.roleCode(),
                "roleName", role.roleName(),
                "active", role.active(),
                "displayOrder", role.displayOrder()
        ));
    }

    private static AuditSnapshot overrideSnapshot(
            List<PermissionOverrideResponse> overrides
    ) {
        List<Map<String, Object>> values = overrides.stream()
                .map(override -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put(
                            "permissionId",
                            override.permissionId()
                    );
                    value.put("effect", override.effect());
                    value.put("expiresAt", override.expiresAt());
                    return value;
                })
                .toList();
        return snapshot(Map.of("overrides", values));
    }

    private static AuditSnapshot assignmentSnapshot(
            List<RobotAssignmentResponse> assignments
    ) {
        List<Map<String, Object>> values = assignments.stream()
                .map(assignment -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("robotId", assignment.robotId());
                    value.put(
                            "assignmentType",
                            assignment.assignmentType()
                    );
                    value.put("expiresAt", assignment.expiresAt());
                    return value;
                })
                .toList();
        return snapshot(Map.of("assignments", values));
    }

    private static AuditSnapshot snapshot(Map<String, ?> values) {
        return AuditSnapshot.allow(values.keySet(), values);
    }

    private static Object nullable(Object value) {
        return value == null ? "NONE" : value;
    }

    private static String normalizeReason(String reason) {
        return reason == null ? null : reason.trim();
    }

    private static String trimNullable(String value) {
        return value == null ? null : value.trim();
    }

    private static BusinessException userNotFound() {
        return new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND);
    }

    private static BusinessException roleNotFound() {
        return new BusinessException(ErrorCode.AUTH_ROLE_NOT_FOUND);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.COMMON_CONFLICT, message);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(
                ErrorCode.COMMON_INVALID_REQUEST,
                message
        );
    }
}
