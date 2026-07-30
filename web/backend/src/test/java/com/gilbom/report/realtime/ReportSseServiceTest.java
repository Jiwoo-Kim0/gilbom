package com.gilbom.report.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportAccessService;
import com.gilbom.report.ReportStatus;

@ExtendWith(MockitoExtension.class)
class ReportSseServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000091");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000092");
    private static final UUID THIRD_USER_ID =
            UUID.fromString("17000000-0000-4000-8000-000000000093");
    private static final UUID REPORT_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000091");
    private static final UUID OTHER_REPORT_ID =
            UUID.fromString("28000000-0000-4000-8000-000000000092");
    private static final Instant NOW =
            Instant.parse("2026-07-30T10:00:00Z");

    @Mock
    private ReportAccessService accessService;

    @Mock
    private SessionSecurityStateRepository sessionStateRepository;

    @Test
    void checksPermissionAndReportScopeWhenSubscribing() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(authenticated(USER_ID)));
        ReportSseService service = newService(2, 3);

        service.subscribe(USER_ID, "session-1", REPORT_ID);

        verify(accessService).requireReport(
                USER_ID,
                REPORT_ID,
                ReportAccessService.VIEW);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void rejectsSubscriptionWhoseSessionWasAlreadyRevoked() {
        when(sessionStateRepository.findBySessionId("revoked"))
                .thenReturn(Optional.empty());
        ReportSseService service = newService(2, 3);

        assertThatThrownBy(() -> service.subscribe(
                USER_ID,
                "revoked",
                null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode.AUTH_UNAUTHENTICATED));
        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void rechecksObjectScopeBeforeEveryReportEvent() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(authenticated(USER_ID)));
        when(accessService.canReceiveEvent(USER_ID, REPORT_ID))
                .thenReturn(false);
        ReportSseService service = newService(2, 3);
        service.subscribe(USER_ID, "session-1", null);

        service.publish(event(REPORT_ID));

        verify(accessService).canReceiveEvent(USER_ID, REPORT_ID);
        assertThat(service.subscriptionCount()).isEqualTo(1);
    }

    @Test
    void reportFilterStopsOtherReportsBeforeScopeLookup() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(authenticated(USER_ID)));
        ReportSseService service = newService(2, 3);
        service.subscribe(USER_ID, "session-1", REPORT_ID);

        service.publish(event(OTHER_REPORT_ID));

        verify(accessService, never()).canReceiveEvent(
                USER_ID,
                OTHER_REPORT_ID);
    }

    @Test
    void heartbeatClosesARevokedSession() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(
                        Optional.of(authenticated(USER_ID)),
                        Optional.empty());
        ReportSseService service = newService(2, 3);
        service.subscribe(USER_ID, "session-1", null);

        service.heartbeat();

        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void heartbeatClosesAnExpiredSession() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(
                        Optional.of(authenticated(USER_ID)),
                        Optional.of(expired(USER_ID)));
        ReportSseService service = newService(2, 3);
        service.subscribe(USER_ID, "session-1", null);

        service.heartbeat();

        assertThat(service.subscriptionCount()).isZero();
    }

    @Test
    void limitsConnectionsPerUser() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(authenticated(USER_ID)));
        ReportSseService service = newService(2, 3);
        service.subscribe(USER_ID, "session-1", null);
        service.subscribe(USER_ID, "session-1", null);

        assertThatThrownBy(() -> service.subscribe(
                USER_ID,
                "session-1",
                null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.errorCode())
                                .isEqualTo(
                                        ErrorCode
                                                .COMMON_RATE_LIMIT_EXCEEDED));
        assertThat(service.subscriptionCount(USER_ID)).isEqualTo(2);
    }

    @Test
    void limitsConnectionsAcrossTheServer() {
        when(sessionStateRepository.findBySessionId("session-1"))
                .thenReturn(Optional.of(authenticated(USER_ID)));
        when(sessionStateRepository.findBySessionId("session-2"))
                .thenReturn(Optional.of(authenticated(OTHER_USER_ID)));
        when(sessionStateRepository.findBySessionId("session-3"))
                .thenReturn(Optional.of(authenticated(THIRD_USER_ID)));
        ReportSseService service = newService(2, 2);
        service.subscribe(USER_ID, "session-1", null);
        service.subscribe(OTHER_USER_ID, "session-2", null);

        assertThatThrownBy(() -> service.subscribe(
                THIRD_USER_ID,
                "session-3",
                null))
                .isInstanceOf(BusinessException.class);
        assertThat(service.subscriptionCount()).isEqualTo(2);
    }

    @Test
    void heartbeatIntervalIsFifteenSeconds() {
        assertThat(ReportSseService.HEARTBEAT_MILLIS)
                .isEqualTo(15_000L);
    }

    private ReportSseService newService(
            int maximumPerUser,
            int maximumTotal
    ) {
        return new ReportSseService(
                accessService,
                sessionStateRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                maximumPerUser,
                maximumTotal);
    }

    private static ReportRealtimeEvent event(UUID reportId) {
        return new ReportRealtimeEvent(
                reportId,
                ReportStatus.IN_REVIEW,
                2,
                false,
                NOW);
    }

    private static SessionSecurityState authenticated(UUID userId) {
        return new SessionSecurityState(
                userId,
                AuthenticationState.AUTHENTICATED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null,
                null,
                0);
    }

    private static SessionSecurityState expired(UUID userId) {
        return new SessionSecurityState(
                userId,
                AuthenticationState.AUTHENTICATED,
                NOW.minusSeconds(1),
                null,
                null,
                0);
    }
}
