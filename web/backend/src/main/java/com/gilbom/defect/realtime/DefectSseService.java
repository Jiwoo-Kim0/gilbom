package com.gilbom.defect.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.auth.session.SessionSecurityStateRepository;
import com.gilbom.defect.DefectAccessService;

/**
 * Swagger 21.16 {@code GET /api/v1/stream/defects}의 구독 관리와 발행이다.
 *
 * <p>{@code RobotSseService}와 같은 규칙을 따른다. 세션이 더 이상 유효하지 않으면
 * 스트림을 끊고, 이벤트마다 수신자의 객체 범위를 다시 판정한다. 구독 시점에 범위를
 * 한 번만 확인하면 배정이 회수된 뒤에도 결함이 계속 흘러간다.
 */
@Service
public class DefectSseService {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final long RECONNECT_MILLIS = 3_000L;

    private final DefectAccessService accessService;
    private final SessionSecurityStateRepository sessionStateRepository;
    private final Clock clock;
    private final Map<UUID, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public DefectSseService(
            DefectAccessService accessService,
            SessionSecurityStateRepository sessionStateRepository,
            Clock clock
    ) {
        this.accessService = accessService;
        this.sessionStateRepository = sessionStateRepository;
        this.clock = clock;
    }

    /**
     * @param patrolAreaId 계약의 query 필터. null이면 접근 가능한 전체 결함을 받는다.
     */
    public SseEmitter subscribe(
            UUID userId,
            String sessionId,
            UUID patrolAreaId
    ) {
        UUID subscriptionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        subscriptions.put(
                subscriptionId,
                new Subscription(userId, sessionId, patrolAreaId, emitter));

        Runnable cleanup = () -> subscriptions.remove(subscriptionId);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        send(
                subscriptionId,
                emitter,
                SseEmitter.event()
                        .id(UUID.randomUUID().toString())
                        .name("CONNECTED")
                        .reconnectTime(RECONNECT_MILLIS)
                        .data(new ConnectionEvent(
                                "CONNECTED",
                                clock.instant())));
        return emitter;
    }

    public void publish(DefectRealtimeEvent event) {
        subscriptions.forEach((subscriptionId, subscription) -> {
            if (!isSessionCurrent(subscription)) {
                close(subscriptionId, subscription.emitter());
                return;
            }
            if (!matchesPatrolAreaFilter(subscription, event)) {
                return;
            }
            if (!accessService.canView(
                    subscription.userId(),
                    event.defectId())) {
                return;
            }
            send(
                    subscriptionId,
                    subscription.emitter(),
                    SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name(event.eventType())
                            .reconnectTime(RECONNECT_MILLIS)
                            .data(DefectSseEventResponse.from(event)));
        });
    }

    @Scheduled(
            fixedDelayString = "${gilbom.defect.sse.heartbeat-ms:25000}",
            initialDelayString = "${gilbom.defect.sse.heartbeat-ms:25000}")
    public void heartbeat() {
        subscriptions.forEach((subscriptionId, subscription) -> {
            if (!isSessionCurrent(subscription)) {
                close(subscriptionId, subscription.emitter());
                return;
            }
            send(
                    subscriptionId,
                    subscription.emitter(),
                    SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name("HEARTBEAT")
                            .data(new HeartbeatEvent(clock.instant())));
        });
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    private static boolean matchesPatrolAreaFilter(
            Subscription subscription,
            DefectRealtimeEvent event
    ) {
        if (subscription.patrolAreaId() == null) {
            return true;
        }
        return subscription.patrolAreaId().equals(event.patrolAreaId());
    }

    private void send(
            UUID subscriptionId,
            SseEmitter emitter,
            SseEmitter.SseEventBuilder event
    ) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            subscriptions.remove(subscriptionId);
            emitter.complete();
        }
    }

    private boolean isSessionCurrent(Subscription subscription) {
        try {
            return sessionStateRepository
                    .findBySessionId(subscription.sessionId())
                    .filter(state ->
                            isAuthenticatedFor(state, subscription.userId()))
                    .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isAuthenticatedFor(
            SessionSecurityState state,
            UUID userId
    ) {
        return state.authenticationState() == AuthenticationState.AUTHENTICATED
                && state.belongsTo(userId)
                && !state.isExpiredAt(clock.instant());
    }

    private void close(UUID subscriptionId, SseEmitter emitter) {
        subscriptions.remove(subscriptionId);
        emitter.complete();
    }

    private record Subscription(
            UUID userId,
            String sessionId,
            UUID patrolAreaId,
            SseEmitter emitter
    ) {
    }

    private record ConnectionEvent(
            String eventType,
            Instant connectedAt
    ) {
    }

    private record HeartbeatEvent(
            Instant sentAt
    ) {
    }
}
