package com.gilbom.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticationSessionCleanupJobTest {

    private static final Instant NOW =
            Instant.parse("2026-08-08T09:43:15Z");

    @Test
    void recordsOnlyTheSafeReasonAndDeletedCount(CapturedOutput output) {
        SessionSecurityStateRepository repository =
                mock(SessionSecurityStateRepository.class);
        AuthenticationSessionProperties properties =
                new AuthenticationSessionProperties();
        when(repository.deleteExpiredSecuritySessions(1_000, NOW))
                .thenReturn(3);
        AuthenticationSessionCleanupJob job =
                new AuthenticationSessionCleanupJob(
                        repository,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );

        job.deleteExpiredSessions();

        verify(repository).deleteExpiredSecuritySessions(1_000, NOW);
        assertThat(output.getOut())
                .contains(
                        "Authentication session cleanup completed: "
                                + "reason=EXPIRED_CLEANUP, deleted=3"
                )
                .doesNotContain("session_id")
                .doesNotContain("user_id");
    }
}
