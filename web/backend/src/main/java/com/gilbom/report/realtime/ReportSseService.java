package com.gilbom.report.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;
import com.gilbom.report.ReportAccessService;

/**
 * 세션에 결합된 보고서 SSE 구독과 최소 변경 이벤트를 관리한다.
 */
@Service
public class ReportSseService {

    static final long HEARTBEAT_MILLIS = 15_000L;
    private static final long EMITTER_TIMEOUT_MILLIS =
            30 * 60 * 1000L;
    private static final long RECONNECT_MILLIS = 3_000L;

    private final ReportAccessService accessService;
    private final SessionSecurityStateRepository sessionStateRepository;
    private final Clock clock;
    private final int maximumConnectionsPerUser;
    private final int maximumConnectionsTotal;
    private final Object subscriptionLock = new Object();
    private final Map<UUID, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public ReportSseService(
            ReportAccessService accessService,
            SessionSecurityStateRepository sessionStateRepository,
            Clock clock,
            @Value("${gilbom.report.sse.max-connections-per-user:3}")
            int maximumConnectionsPerUser,
            @Value("${gilbom.report.sse.max-connections-total:200}")
            int maximumConnectionsTotal
    ) {
        if (maximumConnectionsPerUser < 1
                || maximumConnectionsTotal < 1
                || maximumConnectionsPerUser
                        > maximumConnectionsTotal) {
            throw new IllegalArgumentException(
                    "Report SSE connection limits are invalid");
        }
        this.accessService = accessService;
        this.sessionStateRepository = sessionStateRepository;
        this.clock = clock;
        this.maximumConnectionsPerUser =
                maximumConnectionsPerUser;
        this.maximumConnectionsTotal =
                maximumConnectionsTotal;
    }

    public SseEmitter subscribe(
            UUID userId,
            String sessionId,
            UUID reportId
    ) {
        if (reportId == null) {
            accessService.require(
                    userId,
                    ReportAccessService.VIEW);
        } else {
            accessService.requireReport(
                    userId,
                    reportId,
                    ReportAccessService.VIEW);
        }

        Subscription subscription = new Subscription(
                userId,
                sessionId,
                reportId,
                new SseEmitter(EMITTER_TIMEOUT_MILLIS));
        if (!isSessionCurrent(subscription)) {
            throw new BusinessException(
                    ErrorCode.AUTH_UNAUTHENTICATED);
        }

        UUID subscriptionId = UUID.randomUUID();
        synchronized (subscriptionLock) {
            requireConnectionCapacity(userId);
            subscriptions.put(subscriptionId, subscription);
        }

        Runnable cleanup = () -> remove(subscriptionId);
        subscription.emitter().onCompletion(cleanup);
        subscription.emitter().onTimeout(cleanup);
        subscription.emitter().onError(error -> cleanup.run());

        send(
                subscriptionId,
                subscription.emitter(),
                SseEmitter.event()
                        .id(UUID.randomUUID().toString())
                        .name("CONNECTED")
                        .reconnectTime(RECONNECT_MILLIS)
                        .data(new ConnectionEvent(
                                "CONNECTED",
                                clock.instant())));
        return subscription.emitter();
    }

    /**
     * 같은 보고서를 여러 탭에서 보는 사용자의 범위 질의는 발행 한 번에 한 번만
     * 수행한다. 각 사용자 결과는 공유하지 않고 이벤트마다 새로 계산한다.
     */
    public void publish(ReportRealtimeEvent event) {
        Map<UUID, Boolean> accessByUser = new HashMap<>();
        subscriptions.forEach((subscriptionId, subscription) -> {
            if (!isSessionCurrent(subscription)) {
                close(subscriptionId, subscription.emitter());
                return;
            }
            if (subscription.reportId() != null
                    && !subscription.reportId().equals(
                            event.reportId())) {
                return;
            }
            boolean canReceive = accessByUser.computeIfAbsent(
                    subscription.userId(),
                    userId -> accessService.canReceiveEvent(
                            userId,
                            event.reportId()));
            if (!canReceive) {
                return;
            }
            send(
                    subscriptionId,
                    subscription.emitter(),
                    SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name(ReportRealtimeEvent.EVENT_NAME)
                            .reconnectTime(RECONNECT_MILLIS)
                            .data(event));
        });
    }

    /**
     * heartbeat는 keep-alive와 세션 폐기 감지를 함께 수행한다.
     */
    @Scheduled(
            fixedDelay = HEARTBEAT_MILLIS,
            initialDelay = HEARTBEAT_MILLIS)
    public void heartbeat() {
        subscriptions.forEach((subscriptionId, subscription) -> {
            if (!isSessionCurrent(subscription)) {
                close(subscriptionId, subscription.emitter());
                return;
            }
            send(
                    subscriptionId,
                    subscription.emitter(),
                    SseEmitter.event().comment("heartbeat"));
        });
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    int subscriptionCount(UUID userId) {
        return (int) subscriptions.values().stream()
                .filter(subscription ->
                        subscription.userId().equals(userId))
                .count();
    }

    private void requireConnectionCapacity(UUID userId) {
        if (subscriptions.size() >= maximumConnectionsTotal
                || subscriptionCount(userId)
                        >= maximumConnectionsPerUser) {
            throw new BusinessException(
                    ErrorCode.COMMON_RATE_LIMIT_EXCEEDED);
        }
    }

    private void send(
            UUID subscriptionId,
            SseEmitter emitter,
            SseEmitter.SseEventBuilder event
    ) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            remove(subscriptionId);
            emitter.complete();
        }
    }

    private boolean isSessionCurrent(Subscription subscription) {
        if (subscription.userId() == null
                || subscription.sessionId() == null
                || subscription.sessionId().isBlank()) {
            return false;
        }
        try {
            return sessionStateRepository
                    .findBySessionId(subscription.sessionId())
                    .filter(state -> isAuthenticatedFor(
                            state,
                            subscription.userId()))
                    .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isAuthenticatedFor(
            SessionSecurityState state,
            UUID userId
    ) {
        return state.authenticationState()
                        == AuthenticationState.AUTHENTICATED
                && state.belongsTo(userId)
                && !state.isExpiredAt(clock.instant());
    }

    private void close(UUID subscriptionId, SseEmitter emitter) {
        remove(subscriptionId);
        emitter.complete();
    }

    private void remove(UUID subscriptionId) {
        synchronized (subscriptionLock) {
            subscriptions.remove(subscriptionId);
        }
    }

    private record Subscription(
            UUID userId,
            String sessionId,
            UUID reportId,
            SseEmitter emitter
    ) {
    }

    private record ConnectionEvent(
            String eventType,
            Instant connectedAt
    ) {
    }
}
