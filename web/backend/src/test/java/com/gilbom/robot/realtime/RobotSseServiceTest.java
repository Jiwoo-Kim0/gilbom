package com.gilbom.robot.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.robot.application.RobotScopeService;

@ExtendWith(MockitoExtension.class)
class RobotSseServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000013");
    private static final UUID ROBOT_ID =
            UUID.fromString("8750afcb-d682-4ef6-a90d-f63fcf5c2113");
    private static final String SESSION_ID = "robot-sse-session";
    private static final Instant NOW =
            Instant.parse("2026-07-28T01:00:00Z");

    @Mock
    private RobotScopeService scopeService;

    @Mock
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void checksCurrentObjectScopeBeforePublishingRobotEvent() {
        RobotSseService service = new RobotSseService(
                scopeService,
                sessionStateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.subscribe(USER_ID, SESSION_ID);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.of(authenticatedState()));
        when(scopeService.canView(USER_ID, ROBOT_ID)).thenReturn(false);

        service.publish(RobotRealtimeEvent.telemetryUpdated(
                ROBOT_ID,
                "RBT-013",
                NOW));

        verify(sessionStateRepository).findBySessionId(SESSION_ID);
        verify(scopeService).canView(USER_ID, ROBOT_ID);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void closesSubscriptionAfterSessionRevocation() {
        RobotSseService service = new RobotSseService(
                scopeService,
                sessionStateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.subscribe(USER_ID, SESSION_ID);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.empty());

        service.heartbeat();

        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void blocksTheNextEventImmediatelyAfterAuthorizationRevokesTheSession() {
        RobotSseService service = new RobotSseService(
                scopeService,
                sessionStateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.subscribe(USER_ID, SESSION_ID);
        when(sessionStateRepository.findBySessionId(SESSION_ID))
                .thenReturn(Optional.empty());

        service.publish(RobotRealtimeEvent.telemetryUpdated(
                ROBOT_ID,
                "RBT-013",
                NOW));

        verify(sessionStateRepository).findBySessionId(SESSION_ID);
        verify(scopeService, never()).canView(USER_ID, ROBOT_ID);
        assertThat(service.subscriptionCount()).isZero();
    }

    private SessionSecurityState authenticatedState() {
        return new SessionSecurityState(
                USER_ID,
                AuthenticationState.AUTHENTICATED,
                NOW.plusSeconds(3600),
                null,
                null,
                0);
    }
}
