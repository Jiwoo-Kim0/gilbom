package com.gilbom.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountLoginProtectionRepositoryTest {

    private static final UUID USER_ID =
            UUID.fromString("21500000-0000-4000-8000-000000000001");

    @Test
    void resetsFailuresForEveryActiveAccountIncludingInitialPasswordChange() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), eq(USER_ID))).thenReturn(1);
        AccountLoginProtectionRepository repository =
                new AccountLoginProtectionRepository(jdbcTemplate);

        int affected = repository.resetPasswordFailures(USER_ID);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(USER_ID));
        assertThat(sql.getValue())
                .contains("account_status = 'ACTIVE'")
                .doesNotContain("password_change_required");
    }
}
