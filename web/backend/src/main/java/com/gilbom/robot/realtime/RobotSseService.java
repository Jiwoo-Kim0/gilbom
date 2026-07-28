package com.gilbom.robot.realtime;

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
import com.gilbom.robot.application.RobotScopeService;

@Service
public class RobotSseService {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;
    private static final long RECONNECT_MILLIS = 3_000L;

    private final RobotScopeService scopeService;
    private final SessionSecurityStateRepository sessionStateRepository;
    private final Clock clock;
    private final Map<UUID, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public RobotSseService(
            RobotScopeService scopeService,
            SessionSecurityStateRepository sessionStateRepository,
            Clock clock
    ) {
        this.scopeService = scopeService;
        this.sessionStateRepository = sessionStateRepository;
        this.clock = clock;
    }

    public SseEmitter subscribe(UUID userId, String sessionId) {
        UUID subscriptionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        subscriptions.put(
                subscriptionId,
                new Subscription(userId, sessionId, emitter));

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
                        .data(new ConnectionEvent("CONNECTED", clock.instant())));
        return emitter;
    }

    public void publish(RobotRealtimeEvent event) {
        subscriptions.forEach((subscriptionId, subscription) -> {
            if (!isSessionCurrent(subscription)) {
                close(subscriptionId, subscription.emitter());
                return;
            }
            if (!scopeService.canView(
                    subscription.userId(),
                    event.robotId())) {
                return;
            }
            send(
                    subscriptionId,
                    subscription.emitter(),
                    SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name(event.eventType())
                            .reconnectTime(RECONNECT_MILLIS)
                            .data(RobotSseEventResponse.from(event)));
        });
    }

    @Scheduled(
            fixedDelayString = "${gilbom.robot.sse.heartbeat-ms:25000}",
            initialDelayString = "${gilbom.robot.sse.heartbeat-ms:25000}")
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
                    .filter(state -> isAuthenticatedFor(state, subscription.userId()))
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
