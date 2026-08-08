package com.gilbom.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogService;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.auth.admin.AdminDtos.AccountStatusChange;
import com.gilbom.auth.admin.AdminDtos.ApprovalRequest;
import com.gilbom.auth.admin.AdminDtos.OverrideEffect;
import com.gilbom.auth.admin.AdminDtos.PermissionOverrideItem;
import com.gilbom.auth.admin.AdminDtos.PermissionOverridesRequest;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentItem;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentResponse;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentsRequest;
import com.gilbom.auth.admin.AdminDtos.RobotAssignmentType;
import com.gilbom.auth.admin.AdminDtos.UserResponse;
import com.gilbom.auth.admin.AdminDtos.UserStatusRequest;
import com.gilbom.auth.admin.AdminRepository.PermissionRef;
import com.gilbom.auth.admin.AdminRepository.RoleRef;
import com.gilbom.auth.admin.AdminRepository.UserState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class AdminManagementServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString(
            "42000000-0000-4000-8000-000000000001"
    );
    private static final UUID TARGET_ID = UUID.fromString(
            "42000000-0000-4000-8000-000000000002"
    );
    private static final UUID ROLE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000003"
    );
    private static final UUID PERMISSION_ID = UUID.fromString(
            "00000000-0000-4100-8000-000000000017"
    );
    private static final AuditRequestContext CONTEXT =
            AuditRequestContext.requestIdOnly(UUID.fromString(
                    "42000000-0000-4000-8000-000000000003"
            ));

    @Mock
    private AdminRepository repository;

    @Mock
    private AuditLogService auditLogService;

    private AdminManagementService service;

    @BeforeEach
    void setUp() {
        service = new AdminManagementService(
                repository,
                auditLogService,
                Clock.fixed(
                        Instant.parse("2026-07-28T00:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void approvalLocksAssignsActivatesAndAuditsInOneServiceCall() {
        ApprovalRequest request = new ApprovalRequest(
                List.of(ROLE_ID),
                ROLE_ID,
                " 가입 승인 "
        );
        when(repository.findUserForUpdate(TARGET_ID))
                .thenReturn(Optional.of(new UserState(
                        TARGET_ID,
                        "PENDING_APPROVAL",
                        false
                )));
        when(repository.findActiveRoleRefs(List.of(ROLE_ID)))
                .thenReturn(List.of(new RoleRef(
                        ROLE_ID,
                        "OPERATOR",
                        true
                )));
        when(repository.approvePendingUser(TARGET_ID)).thenReturn(1);
        when(repository.findUser(TARGET_ID))
                .thenReturn(Optional.of(user("ACTIVE", true)));

        UserResponse response = service.approveRegistration(
                TARGET_ID,
                request,
                ACTOR_ID,
                CONTEXT
        );

        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
        InOrder order = inOrder(repository, auditLogService);
        order.verify(repository).lockLastAdminGuard();
        order.verify(repository).findUserForUpdate(TARGET_ID);
        order.verify(repository).findActiveRoleRefs(List.of(ROLE_ID));
        order.verify(repository).replaceUserRoles(
                TARGET_ID,
                List.of(ROLE_ID),
                ROLE_ID,
                ACTOR_ID
        );
        order.verify(repository).approvePendingUser(TARGET_ID);
        order.verify(auditLogService).appendRequired(any());

        ArgumentCaptor<AuditLogRecord> audit =
                ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).appendRequired(audit.capture());
        assertThat(audit.getValue().actionType())
                .isEqualTo("ADMIN_REGISTRATION_APPROVED");
        assertThat(audit.getValue().reason()).isEqualTo("가입 승인");
    }

    @Test
    void lastActiveAdminCannotBeDisabled() {
        when(repository.findUserForUpdate(TARGET_ID))
                .thenReturn(Optional.of(new UserState(
                        TARGET_ID,
                        "ACTIVE",
                        true
                )));
        when(repository.isActiveSystemAdmin(TARGET_ID))
                .thenReturn(true);
        when(repository.countActiveSystemAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> service.changeUserStatus(
                TARGET_ID,
                new UserStatusRequest(
                        AccountStatusChange.DISABLED,
                        "담당 변경"
                ),
                ACTOR_ID,
                CONTEXT
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.AUTH_LAST_ADMIN_PROTECTED);

        verify(repository).lockLastAdminGuard();
        verify(repository, never()).changeUserStatus(any(), any());
        verify(auditLogService, never()).appendRequired(any());
    }

    @Test
    void adminSettingsCannotBeGrantedThroughUserAllowOverride() {
        PermissionOverridesRequest request =
                new PermissionOverridesRequest(
                        List.of(new PermissionOverrideItem(
                                PERMISSION_ID,
                                OverrideEffect.ALLOW,
                                null,
                                "긴급 권한"
                        )),
                        "권한 교체"
                );
        when(repository.findUserForUpdate(TARGET_ID))
                .thenReturn(Optional.of(new UserState(
                        TARGET_ID,
                        "ACTIVE",
                        true
                )));
        when(repository.findActivePermissionRefs(
                List.of(PERMISSION_ID)
        )).thenReturn(List.of(new PermissionRef(
                PERMISSION_ID,
                "ADMIN_SETTINGS_MANAGE"
        )));

        assertThatThrownBy(() -> service.replacePermissionOverrides(
                TARGET_ID,
                request,
                ACTOR_ID,
                CONTEXT
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

        verify(repository, never()).replacePermissionOverrides(
                any(),
                any(),
                any(),
                any()
        );
        verify(auditLogService, never()).appendRequired(any());
    }

    @Test
    void identicalRobotAssignmentsDoNotReplaceOrRevokeSessions() {
        UUID robotId = UUID.fromString(
                "42000000-0000-4000-8000-000000000004"
        );
        Instant expiresAt = Instant.parse("2026-08-01T00:00:00Z");
        RobotAssignmentItem item = new RobotAssignmentItem(
                robotId,
                RobotAssignmentType.OPERATOR,
                expiresAt,
                " 동일 배정 "
        );
        RobotAssignmentResponse existing = new RobotAssignmentResponse(
                UUID.fromString(
                        "42000000-0000-4000-8000-000000000005"
                ),
                robotId,
                "로봇 1",
                RobotAssignmentType.OPERATOR,
                "동일 배정",
                Instant.parse("2026-07-27T00:00:00Z"),
                expiresAt
        );
        when(repository.findUserForUpdate(TARGET_ID))
                .thenReturn(Optional.of(new UserState(
                        TARGET_ID,
                        "ACTIVE",
                        true
                )));
        when(repository.countActiveRobots(List.of(robotId)))
                .thenReturn(1);
        when(repository.findRobotAssignments(TARGET_ID))
                .thenReturn(List.of(existing));

        List<RobotAssignmentResponse> result =
                service.replaceRobotAssignments(
                        TARGET_ID,
                        new RobotAssignmentsRequest(
                                List.of(item),
                                "변경 없는 재저장"
                        ),
                        ACTOR_ID,
                        CONTEXT
                );

        assertThat(result).containsExactly(existing);
        verify(repository, never()).replaceRobotAssignments(
                any(),
                any(),
                any(),
                any()
        );
        verify(auditLogService, never()).appendRequired(any());
    }

    private static UserResponse user(String status, boolean active) {
        return new UserResponse(
                TARGET_ID,
                "USR-SERVICE-TEST",
                "service@example.test",
                "서비스 테스트",
                null,
                null,
                null,
                status,
                active,
                false,
                List.of(),
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:00:00Z")
        );
    }
}
