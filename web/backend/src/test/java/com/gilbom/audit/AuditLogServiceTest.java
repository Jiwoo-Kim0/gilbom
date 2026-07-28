package com.gilbom.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditLogServiceTest {

    private final AuditLogRepository repository =
            Mockito.mock(AuditLogRepository.class);
    private final BestEffortAuditLogWriter bestEffortWriter =
            Mockito.mock(BestEffortAuditLogWriter.class);
    private final AuditLogService service =
            new AuditLogService(repository, bestEffortWriter);

    @Test
    void requiredAuditFailurePropagatesToTheOwningTransaction() {
        AuditLogRecord record = record();
        Mockito.doThrow(new DataAccessResourceFailureException(
                "secret=must-not-be-logged"
        )).when(repository).append(record);

        assertThatThrownBy(() -> service.appendRequired(record))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void bestEffortAuditFailureDoesNotChangeTheCompletedOutcome() {
        AuditLogRecord record = record();
        Mockito.doThrow(new DataAccessResourceFailureException(
                "secret=must-not-be-logged"
        )).when(bestEffortWriter)
                .appendInIndependentTransaction(record);

        assertThatCode(() -> service.appendBestEffort(record))
                .doesNotThrowAnyException();
    }

    private static AuditLogRecord record() {
        return new AuditLogRecord(
                AuditRequestContext.system(),
                null,
                AuditActorType.SYSTEM,
                "AUDIT_TEST_EVENT",
                AuditTargetType.SETTING,
                null,
                null,
                null,
                null,
                AuditResult.SUCCESS
        );
    }
}
