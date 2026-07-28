package com.gilbom.auth.audit;

import org.springframework.stereotype.Repository;

import com.gilbom.audit.AuditActorType;
import com.gilbom.audit.AuditLogRecord;
import com.gilbom.audit.AuditLogRepository;
import com.gilbom.audit.AuditRequestContext;
import com.gilbom.audit.AuditResult;
import com.gilbom.audit.AuditTargetType;

@Repository
public class AuthenticationAuditRepository {

    private final AuditLogRepository repository;

    public AuthenticationAuditRepository(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void append(AuthenticationAuditEvent event) {
        AuthenticationRequestAuditContext context =
                event.requestContext();
        repository.append(new AuditLogRecord(
                new AuditRequestContext(
                        context.requestId(),
                        context.ipAddress(),
                        context.userAgent()
                ),
                event.actorUserId(),
                AuditActorType.valueOf(event.actorType().name()),
                event.action().databaseValue(),
                AuditTargetType.valueOf(event.targetType().name()),
                event.targetId(),
                null,
                null,
                null,
                AuditResult.valueOf(event.result().name())
        ));
    }
}
