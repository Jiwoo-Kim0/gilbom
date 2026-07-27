package com.gilbom.auth.mfa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gilbom.auth.mfa.RecoveryCodeService.GeneratedRecoveryCode;
import com.gilbom.auth.mfa.crypto.EncryptedTotpSecret;

@Repository
public class MfaFactorRepository {

    private final JdbcTemplate jdbcTemplate;

    public MfaFactorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<EnrollmentAccount> findEligibleAccount(UUID userId) {
        List<EnrollmentAccount> accounts = jdbcTemplate.query(
                """
                SELECT user_id, email
                FROM auth.admin_user
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND is_active = TRUE
                  AND password_change_required = FALSE
                """,
                (resultSet, rowNumber) -> new EnrollmentAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("email")
                ),
                userId
        );
        return accounts.stream().findFirst();
    }

    public boolean hasActiveTotpFactor(UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM auth.mfa_factor
                    WHERE user_id = ?
                      AND factor_type = 'TOTP'
                      AND factor_status = 'ACTIVE'
                )
                """,
                Boolean.class,
                userId
        );
        return Boolean.TRUE.equals(exists);
    }

    public int revokePendingTotpFactors(UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_factor
                SET factor_status = 'REVOKED',
                    revoked_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                WHERE user_id = ?
                  AND factor_type = 'TOTP'
                  AND factor_status = 'PENDING'
                """,
                userId
        );
    }

    public int insertPendingTotpFactor(
            UUID factorId,
            UUID userId,
            EncryptedTotpSecret encryptedSecret
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO auth.mfa_factor (
                    factor_id,
                    user_id,
                    factor_type,
                    factor_status,
                    secret_ciphertext,
                    secret_nonce,
                    secret_key_version,
                    encryption_algorithm
                )
                SELECT
                    ?, user_id, 'TOTP', 'PENDING',
                    ?, ?, ?, 'AES-256-GCM'
                FROM auth.admin_user
                WHERE user_id = ?
                  AND account_status = 'ACTIVE'
                  AND is_active = TRUE
                  AND password_change_required = FALSE
                  AND NOT EXISTS (
                    SELECT 1
                    FROM auth.mfa_factor active_factor
                    WHERE active_factor.user_id = auth.admin_user.user_id
                      AND active_factor.factor_type = 'TOTP'
                      AND active_factor.factor_status = 'ACTIVE'
                  )
                """,
                factorId,
                encryptedSecret.ciphertext(),
                encryptedSecret.nonce(),
                encryptedSecret.keyVersion(),
                userId
        );
    }

    public Optional<MfaFactor> findCurrentTotpFactor(
            UUID userId,
            MfaFactorStatus status
    ) {
        List<MfaFactor> factors = jdbcTemplate.query(
                """
                SELECT
                    factor.factor_id,
                    factor.user_id,
                    factor.factor_status,
                    factor.secret_ciphertext,
                    factor.secret_nonce,
                    factor.secret_key_version,
                    factor.last_successful_totp_time_step
                FROM auth.mfa_factor factor
                JOIN auth.admin_user admin_user
                  ON admin_user.user_id = factor.user_id
                WHERE factor.user_id = ?
                  AND factor.factor_type = 'TOTP'
                  AND factor.factor_status = ?
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                """,
                MfaFactorRepository::mapFactor,
                userId,
                status.name()
        );
        return factors.stream().findFirst();
    }

    public int activatePendingFactor(
            UUID userId,
            UUID factorId,
            long successfulTimeStep
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_factor factor
                SET factor_status = 'ACTIVE',
                    last_successful_totp_time_step = ?,
                    activated_at = statement_timestamp(),
                    last_used_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                FROM auth.admin_user admin_user
                WHERE factor.user_id = admin_user.user_id
                  AND factor.user_id = ?
                  AND factor.factor_id = ?
                  AND factor.factor_type = 'TOTP'
                  AND factor.factor_status = 'PENDING'
                  AND factor.last_successful_totp_time_step IS NULL
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                """,
                successfulTimeStep,
                userId,
                factorId
        );
    }

    public int advanceActiveTotpTimeStep(
            UUID userId,
            UUID factorId,
            long successfulTimeStep
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_factor factor
                SET last_successful_totp_time_step = ?,
                    last_used_at = statement_timestamp(),
                    updated_at = statement_timestamp()
                FROM auth.admin_user admin_user
                WHERE factor.user_id = admin_user.user_id
                  AND factor.user_id = ?
                  AND factor.factor_id = ?
                  AND factor.factor_type = 'TOTP'
                  AND factor.factor_status = 'ACTIVE'
                  AND (
                    factor.last_successful_totp_time_step IS NULL
                    OR factor.last_successful_totp_time_step < ?
                  )
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                """,
                successfulTimeStep,
                userId,
                factorId,
                successfulTimeStep
        );
    }

    public int insertRecoveryCodeHashes(
            UUID factorId,
            List<GeneratedRecoveryCode> recoveryCodes
    ) {
        if (recoveryCodes == null
                || recoveryCodes.size() != RecoveryCodeService.CODE_COUNT) {
            throw new IllegalArgumentException(
                    "Exactly ten recovery code hashes are required"
            );
        }
        int[][] results = jdbcTemplate.batchUpdate(
                """
                INSERT INTO auth.mfa_recovery_code (
                    factor_id,
                    code_position,
                    code_hash
                ) VALUES (?, ?, ?)
                """,
                recoveryCodes,
                recoveryCodes.size(),
                (statement, code) -> {
                    statement.setObject(1, factorId);
                    statement.setInt(2, code.position());
                    statement.setString(3, code.encodedHash());
                }
        );
        int inserted = 0;
        for (int[] batch : results) {
            for (int affected : batch) {
                if (affected > 0) {
                    inserted += affected;
                }
            }
        }
        return inserted;
    }

    public Optional<RecoveryCodeRecord> findRecoveryCode(
            UUID factorId,
            int position
    ) {
        List<RecoveryCodeRecord> codes = jdbcTemplate.query(
                """
                SELECT
                    recovery_code_id,
                    factor_id,
                    code_position,
                    code_hash,
                    used_at,
                    expires_at
                FROM auth.mfa_recovery_code
                WHERE factor_id = ?
                  AND code_position = ?
                """,
                (resultSet, rowNumber) -> new RecoveryCodeRecord(
                        resultSet.getObject(
                                "recovery_code_id",
                                UUID.class
                        ),
                        resultSet.getObject("factor_id", UUID.class),
                        resultSet.getInt("code_position"),
                        resultSet.getString("code_hash"),
                        toInstant(resultSet.getTimestamp("used_at")),
                        toInstant(resultSet.getTimestamp("expires_at"))
                ),
                factorId,
                position
        );
        return codes.stream().findFirst();
    }

    public int consumeRecoveryCode(
            UUID userId,
            UUID factorId,
            UUID recoveryCodeId
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth.mfa_recovery_code recovery_code
                SET used_at = statement_timestamp()
                FROM auth.mfa_factor factor,
                     auth.admin_user admin_user
                WHERE recovery_code.factor_id = factor.factor_id
                  AND factor.user_id = admin_user.user_id
                  AND recovery_code.recovery_code_id = ?
                  AND recovery_code.factor_id = ?
                  AND factor.user_id = ?
                  AND recovery_code.used_at IS NULL
                  AND (
                    recovery_code.expires_at IS NULL
                    OR recovery_code.expires_at > statement_timestamp()
                  )
                  AND factor.factor_type = 'TOTP'
                  AND factor.factor_status = 'ACTIVE'
                  AND admin_user.account_status = 'ACTIVE'
                  AND admin_user.is_active = TRUE
                  AND admin_user.password_change_required = FALSE
                """,
                recoveryCodeId,
                factorId,
                userId
        );
    }

    private static MfaFactor mapFactor(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        long timeStep = resultSet.getLong(
                "last_successful_totp_time_step"
        );
        Long nullableTimeStep =
                resultSet.wasNull() ? null : timeStep;
        return new MfaFactor(
                resultSet.getObject("factor_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                MfaFactorStatus.valueOf(
                        resultSet.getString("factor_status")
                ),
                new EncryptedTotpSecret(
                        resultSet.getBytes("secret_ciphertext"),
                        resultSet.getBytes("secret_nonce"),
                        resultSet.getInt("secret_key_version")
                ),
                nullableTimeStep
        );
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record EnrollmentAccount(UUID userId, String email) {
    }

    public record MfaFactor(
            UUID factorId,
            UUID userId,
            MfaFactorStatus status,
            EncryptedTotpSecret encryptedSecret,
            Long lastSuccessfulTimeStep
    ) {
    }

    public record RecoveryCodeRecord(
            UUID recoveryCodeId,
            UUID factorId,
            int position,
            String encodedHash,
            Instant usedAt,
            Instant expiresAt
    ) {
        public boolean isAvailableAt(Instant now) {
            return usedAt == null
                    && (expiresAt == null || expiresAt.isAfter(now));
        }
    }
}
