package com.gilbom.report.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class ReportRealtimeEventListenerTest {

    @Test
    void publishesOnlyAfterTheReportTransactionCommits()
            throws Exception {
        Method listener = ReportRealtimeEventListener.class
                .getMethod(
                        "onReportRealtimeEvent",
                        ReportRealtimeEvent.class);
        TransactionalEventListener annotation =
                listener.getAnnotation(
                        TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
