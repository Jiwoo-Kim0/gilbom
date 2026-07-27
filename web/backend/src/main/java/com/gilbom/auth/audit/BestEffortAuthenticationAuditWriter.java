package com.gilbom.auth.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BestEffortAuthenticationAuditWriter {

    private final AuthenticationAuditRepository repository;

    public BestEffortAuthenticationAuditWriter(
            AuthenticationAuditRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendInIndependentTransaction(
            AuthenticationAuditEvent event
    ) {
        repository.append(event);
    }
}
