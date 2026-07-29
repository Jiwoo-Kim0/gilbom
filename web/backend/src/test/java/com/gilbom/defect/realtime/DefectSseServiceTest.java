package com.gilbom.defect.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.defect.DefectAccessService;

@ExtendWith(MockitoExtension.class)
class DefectSseServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000041");
    private static final UUID DEFECT_ID =
            UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000041");
    private static final UUID PATROL_AREA_ID =
            UUID.fromString("3c9a2f4e-1c4d-4a5b-9c8d-000000000041");
    private static final UUID OTHER_AREA_ID =
            UUID.fromString("3c9a2f4e-1c4d-4a5b-9c8d-000000000042");
    private static final String SESSION_ID = "defect-sse-session";
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");

    @Mock
    private DefectAccessService accessService;

    @Mock
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void checksCurrentObjectScopeBeforePublishingDefectEvent() {
        DefectSseService service = newService();
        service.subscribe(USER_ID, SESSION_ID, null);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(authenticatedState()));
        when(accessService.canView(USER_ID, DEFECT_ID)).thenReturn(false);

        service.publish(statusUpdatedEvent(PATROL_AREA_ID));

        // 범위 밖이면 보내지 않지만 구독은 유지한다.
        verify(accessService).canView(USER_ID, DEFECT_ID);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void appliesPatrolAreaFilterWithoutQueryingScope() {
        DefectSseService service = newService();
        service.subscribe(USER_ID, SESSION_ID, PATROL_AREA_ID);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(authenticatedState()));

        service.publish(statusUpdatedEvent(OTHER_AREA_ID));

        // 구역이 다르면 범위 판정 질의까지 갈 필요가 없다.
        verify(accessService, never()).canView(USER_ID, DEFECT_ID);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void deliversEventMatchingPatrolAreaFilter() {
        DefectSseService service = newService();
        service.subscribe(USER_ID, SESSION_ID, PATROL_AREA_ID);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(authenticatedState()));
        when(accessService.canView(USER_ID, DEFECT_ID)).thenReturn(true);

        service.publish(statusUpdatedEvent(PATROL_AREA_ID));

        verify(accessService).canView(USER_ID, DEFECT_ID);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void closesSubscriptionAfterSessionRevocation() {
        DefectSseService service = newService();
        service.subscribe(USER_ID, SESSION_ID, null);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.empty());

        service.heartbeat();

        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void stopsPublishingToRevokedSession() {
        DefectSseService service = newService();
        service.subscribe(USER_ID, SESSION_ID, null);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.empty());

        service.publish(statusUpdatedEvent(PATROL_AREA_ID));

        verify(accessService, never()).canView(USER_ID, DEFECT_ID);
        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void usesContractEventNames() {
        assertThat(DefectRealtimeEvent.CREATED).isEqualTo("defect.created");
        assertThat(DefectRealtimeEvent.UPDATED).isEqualTo("defect.updated");
        assertThat(statusUpdatedEvent(PATROL_AREA_ID).eventType())
                .isEqualTo("defect.updated");
        assertThat(DefectRealtimeEvent.created(
                DEFECT_ID,
                "INC-20260729-000001",
                PATROL_AREA_ID,
                "DAMAGED",
                "HIGH",
                "NEW",
                127.01,
                37.51,
                new BigDecimal("0.870"),
                NOW).eventType())
                .isEqualTo("defect.created");
    }

    @Test
    void mergedEventCarriesRepresentativeDefectId() {
        UUID representativeId =
                UUID.fromString("2b9a2f4e-1c4d-4a5b-9c8d-000000000049");
        DefectRealtimeEvent event = DefectRealtimeEvent.mergedInto(
                DEFECT_ID,
                "INC-20260729-000001",
                PATROL_AREA_ID,
                "DAMAGED",
                "HIGH",
                "NEW",
                127.01,
                37.51,
                representativeId,
                NOW);

        // 병합도 계약상 별도 이벤트명이 없으므로 defect.updated로 나간다.
        assertThat(event.eventType()).isEqualTo("defect.updated");
        assertThat(event.mergedIntoDefectId()).isEqualTo(representativeId);
        assertThat(event.changedFields())
                .containsExactly("mergedIntoDefectId");
    }

    private DefectSseService newService() {
        return new DefectSseService(
                accessService,
                sessionStateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DefectRealtimeEvent statusUpdatedEvent(UUID patrolAreaId) {
        return DefectRealtimeEvent.updated(
                DEFECT_ID,
                "INC-20260729-000001",
                patrolAreaId,
                "DAMAGED",
                "HIGH",
                "ACCEPTED",
                127.01,
                37.51,
                "NEW",
                "ACCEPTED",
                List.of("workflowStatus"),
                NOW);
    }

    private static SessionSecurityState authenticatedState() {
        return new SessionSecurityState(
                USER_ID,
                AuthenticationState.AUTHENTICATED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null,
                null,
                0);
    }
}
