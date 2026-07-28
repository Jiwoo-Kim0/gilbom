package com.gilbom.robot.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RobotRealtimeEventListener {

    private final RobotSseService sseService;

    public RobotRealtimeEventListener(RobotSseService sseService) {
        this.sseService = sseService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRobotRealtimeEvent(RobotRealtimeEvent event) {
        sseService.publish(event);
    }
}
