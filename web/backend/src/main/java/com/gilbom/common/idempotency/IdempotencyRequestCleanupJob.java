package com.gilbom.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyRequestCleanupJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    IdempotencyRequestCleanupJob.class);
    private static final int CLEANUP_BATCH_SIZE = 1_000;

    private final IdempotencyRequestStore requestStore;

    public IdempotencyRequestCleanupJob(
            IdempotencyRequestStore requestStore
    ) {
        this.requestStore = requestStore;
    }

    @Scheduled(
            initialDelayString = "${gilbom.idempotency.cleanup-interval:10m}",
            fixedDelayString = "${gilbom.idempotency.cleanup-interval:10m}"
    )
    public void deleteExpiredRequests() {
        try {
            requestStore.deleteExpiredBatch(CLEANUP_BATCH_SIZE);
        } catch (DataAccessException exception) {
            // SQL이나 저장된 요청 정보를 scheduled task log에 노출하지 않는다.
            LOGGER.warn(
                    "Expired idempotency request cleanup was unavailable");
        }
    }
}
