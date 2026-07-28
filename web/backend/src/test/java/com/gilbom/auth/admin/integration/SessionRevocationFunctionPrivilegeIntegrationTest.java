package com.gilbom.auth.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SessionRevocationFunctionPrivilegeIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres");
    private static final String APPLICATION_ROLE =
            "gilbom_session_revocation_app";
    private static final String APPLICATION_PASSWORD =
            "session-revocation-test-password";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>(POSTGIS_IMAGE)
                    .withDatabaseName("gilbom_session_privilege_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static JdbcTemplate ownerJdbcTemplate;
    private static JdbcTemplate applicationJdbcTemplate;

    @BeforeAll
    static void migrateWithDistinctApplicationRole() {
        DataSource ownerDataSource = dataSource(
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        ownerJdbcTemplate = new JdbcTemplate(ownerDataSource);
        ownerJdbcTemplate.execute("""
                CREATE ROLE gilbom_session_revocation_app
                LOGIN
                PASSWORD 'session-revocation-test-password'
                NOSUPERUSER
                NOCREATEDB
                NOCREATEROLE
                NOINHERIT
                """);

        Flyway.configure()
                .dataSource(ownerDataSource)
                .defaultSchema("flyway_history")
                .placeholders(Map.of(
                        "applicationRole",
                        APPLICATION_ROLE
                ))
                .load()
                .migrate();

        applicationJdbcTemplate = new JdbcTemplate(dataSource(
                APPLICATION_ROLE,
                APPLICATION_PASSWORD
        ));
    }

    @Test
    @Tag("security-acceptance")
    void applicationRoleCanExecuteOnlyExplicitlyGrantedSessionFunction() {
        Boolean executable = applicationJdbcTemplate.queryForObject(
                """
                SELECT has_function_privilege(
                    current_user,
                    'auth.revoke_user_authorization_sessions(uuid)',
                    'EXECUTE'
                )
                """,
                Boolean.class
        );
        Integer deletedSessions = applicationJdbcTemplate.queryForObject(
                "SELECT auth.revoke_user_authorization_sessions(?)",
                Integer.class,
                UUID.randomUUID()
        );
        List<String> grantees = ownerJdbcTemplate.queryForList(
                """
                SELECT grantee
                FROM information_schema.routine_privileges
                WHERE routine_schema = 'auth'
                  AND routine_name =
                      'revoke_user_authorization_sessions'
                  AND privilege_type = 'EXECUTE'
                ORDER BY grantee
                """,
                String.class
        );

        assertThat(executable).isTrue();
        assertThat(deletedSessions).isZero();
        assertThat(grantees)
                .contains(APPLICATION_ROLE, DATABASE.getUsername())
                .doesNotContain("PUBLIC");
    }

    private static DataSource dataSource(
            String username,
            String password
    ) {
        return new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                username,
                password
        );
    }
}
