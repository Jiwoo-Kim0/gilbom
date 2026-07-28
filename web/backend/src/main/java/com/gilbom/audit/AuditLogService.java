package com.gilbom.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;
    private final BestEffortAuditLogWriter bestEffortWriter;

    public AuditLogService(
            AuditLogRepository repository,
            BestEffortAuditLogWriter bestEffortWriter
    ) {
        this.repository = repository;
        this.bestEffortWriter = bestEffortWriter;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendRequired(AuditLogRecord record) {
        repository.append(record);
    }

    public void appendBestEffort(AuditLogRecord record) {
        try {
            bestEffortWriter.appendInIndependentTransaction(record);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Best-effort audit was unavailable. requestId={}",
                    record.requestContext().requestId()
            );
        }
    }
}
