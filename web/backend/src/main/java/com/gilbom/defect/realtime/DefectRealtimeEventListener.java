package com.gilbom.defect.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋된 변경만 스트림으로 내보낸다. 트랜잭션이 롤백되면 이벤트도 발행되지 않으므로
 * 화면이 존재하지 않는 상태를 표시하지 않는다.
 */
@Component
public class DefectRealtimeEventListener {

    private final DefectSseService sseService;

    public DefectRealtimeEventListener(DefectSseService sseService) {
        this.sseService = sseService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDefectRealtimeEvent(DefectRealtimeEvent event) {
        sseService.publish(event);
    }
}
