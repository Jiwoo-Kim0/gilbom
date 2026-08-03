package com.gilbom.auth.login;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gilbom.auth.AuthenticationDefenseService;
import com.gilbom.auth.account.EmailAddressPolicy;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.login.LoginAccountRepository.LoginAccount;
import com.gilbom.auth.mfa.MfaAuthenticationCompletionService;
import com.gilbom.auth.model.AuthenticationEvent;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.ratelimit.AuthenticationRateLimitExceededException;
import com.gilbom.auth.ratelimit.RateLimitDecision;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class AuthenticationLoginService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthenticationLoginService.class);
    private static final String INVALID_CREDENTIALS_MESSAGE =
            "인증 정보가 올바르지 않습니다.";

    private final LoginAccountRepository accountRepository;
    private final PasswordHashingService passwordHashingService;
    private final AuthenticationDefenseService defenseService;
    private final MfaAuthenticationCompletionService completionService;
    private final AuthenticationAuditService auditService;
    private final Clock clock;

    public AuthenticationLoginService(
            LoginAccountRepository accountRepository,
            PasswordHashingService passwordHashingService,
            AuthenticationDefenseService defenseService,
            MfaAuthenticationCompletionService completionService,
            AuthenticationAuditService auditService,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.passwordHashingService = passwordHashingService;
        this.defenseService = defenseService;
        this.completionService = completionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public LoginResponse login(
            LoginRequest loginRequest,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        String normalizedEmail =
                EmailAddressPolicy.normalize(loginRequest.email());
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(servletRequest);
        Optional<LoginAccount> account =
                accountRepository.findByNormalizedEmail(normalizedEmail);

        RateLimitDecision accountLimit =
                defenseService.inspectPasswordAttempt(normalizedEmail);
        if (!accountLimit.allowed()) {
            auditService.recordRateLimitedBestEffort(
                    auditContext,
                    account.map(LoginAccount::userId).orElse(null)
            );
            throw new AuthenticationRateLimitExceededException(
                    accountLimit.retryAfterSeconds()
            );
        }

        String storedPasswordHash =
                account.map(LoginAccount::passwordHash).orElse(null);
        boolean passwordMatches = verifyPasswordSafely(
                loginRequest.password(),
                storedPasswordHash,
                auditContext
        );
        boolean eligible = account
                .filter(value -> passwordMatches)
                .filter(value -> value.authenticationState()
                        .canAttemptAuthenticationAt(clock.instant()))
                .isPresent();

        if (!eligible) {
            defenseService.recordPasswordFailure(
                    normalizedEmail,
                    account.map(LoginAccount::userId).orElse(null),
                    auditContext
            );
            throw unauthenticated();
        }

        LoginAccount verifiedAccount = account.orElseThrow();
        LoginChallengeResponse challenge = LoginChallengeResponse.from(
                defenseService.completePasswordVerification(
                        normalizedEmail,
                        verifiedAccount.userId(),
                        nextEvent(verifiedAccount),
                        servletRequest,
                        auditContext
                )
        );
        if (verifiedAccount.mfaRequired()
                || verifiedAccount.passwordChangeRequired()) {
            return challenge;
        }
        return completionService.completeMfaBypass(
                servletRequest,
                servletResponse,
                verifiedAccount.userId(),
                auditContext
        );
    }

    private boolean verifyPasswordSafely(
            String rawPassword,
            String storedPasswordHash,
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return passwordHashingService.verifyAgainstNullableHash(
                    rawPassword,
                    storedPasswordHash
            );
        } catch (RuntimeException exception) {
            // hash·입력·예외 원문은 기록하지 않고 운영자가 분류 가능한 최소 정보만 남긴다.
            LOGGER.error(
                    "Argon2id password verification failed safely. "
                            + "requestId={} category=PASSWORD_HASH_CONFIGURATION",
                    auditContext.requestId()
            );
            return false;
        }
    }

    private static AuthenticationEvent nextEvent(LoginAccount account) {
        if (account.passwordChangeRequired()) {
            return AuthenticationEvent.PASSWORD_VERIFIED_CHANGE_REQUIRED;
        }
        if (!account.mfaRequired()) {
            // 같은 transaction에서 정책 예외를 확인한 뒤 즉시 승격하는 내부
            // challenge이며 클라이언트에는 노출하지 않는다.
            return AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED;
        }
        return account.mfaEnrolled()
                ? AuthenticationEvent.PASSWORD_VERIFIED_MFA_REQUIRED
                : AuthenticationEvent
                .PASSWORD_VERIFIED_MFA_ENROLLMENT_REQUIRED;
    }

    private static BusinessException unauthenticated() {
        return new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                INVALID_CREDENTIALS_MESSAGE
        );
    }
}
