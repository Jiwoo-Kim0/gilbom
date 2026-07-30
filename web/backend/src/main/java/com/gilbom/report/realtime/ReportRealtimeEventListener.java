package com.gilbom.report.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 보고서·버전·감사가 모두 커밋된 뒤에만 변경 알림을 전송한다.
 */
@Component
public class ReportRealtimeEventListener {

    private final ReportSseService sseService;

    public ReportRealtimeEventListener(ReportSseService sseService) {
        this.sseService = sseService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportRealtimeEvent(ReportRealtimeEvent event) {
        sseService.publish(event);
    }
}
