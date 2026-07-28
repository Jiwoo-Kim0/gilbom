package com.gilbom.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BestEffortAuditLogWriter {

    private final AuditLogRepository repository;

    public BestEffortAuditLogWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendInIndependentTransaction(AuditLogRecord record) {
        repository.append(record);
    }
}
