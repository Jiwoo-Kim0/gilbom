package com.gilbom.auth.login;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gilbom.auth.account.AccountStatus;
import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.login.LoginAccountRepository.LoginAccount;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.SessionSecurityState;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class InitialPasswordChangeService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InitialPasswordChangeService.class);

    private final LoginAccountRepository accountRepository;
    private final PasswordHashingService passwordHashingService;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationAuditService auditService;
    private final InitialPasswordChangeCommitService commitService;

    public InitialPasswordChangeService(
            LoginAccountRepository accountRepository,
            PasswordHashingService passwordHashingService,
            AuthenticationSessionService sessionService,
            AuthenticationAuditService auditService,
            InitialPasswordChangeCommitService commitService
    ) {
        this.accountRepository = accountRepository;
        this.passwordHashingService = passwordHashingService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.commitService = commitService;
    }

    public LoginChallengeResponse change(
            InitialPasswordChangeRequest changeRequest,
            HttpServletRequest servletRequest
    ) {
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(servletRequest);
        Optional<SessionSecurityState> currentState =
                sessionService.findCurrentState(servletRequest);
        UUID targetUserId = currentState
                .map(SessionSecurityState::userId)
                .orElse(null);

        if (currentState.isEmpty()
                || currentState.get().authenticationState()
                    != AuthenticationState.PASSWORD_CHANGE_REQUIRED
                || targetUserId == null) {
            return reject(auditContext, targetUserId);
        }

        LoginAccount account = accountRepository.findByUserId(targetUserId)
                .filter(value -> value.authenticationState().accountStatus()
                        == AccountStatus.ACTIVE)
                .filter(LoginAccount::passwordChangeRequired)
                .orElse(null);
        if (account == null) {
            return reject(auditContext, targetUserId);
        }

        final boolean matchesCurrentPassword;
        try {
            matchesCurrentPassword =
                    passwordHashingService.verifyAgainstNullableHash(
                            changeRequest.newPassword(),
                            account.passwordHash()
                    );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Initial password verification failed safely. "
                            + "requestId={} "
                            + "category=PASSWORD_HASH_CONFIGURATION",
                    auditContext.requestId()
            );
            return reject(auditContext, targetUserId);
        }

        if (matchesCurrentPassword) {
            auditService.recordInitialPasswordChangeFailedRequired(
                    auditContext,
                    targetUserId
            );
            throw new BusinessException(
                    ErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED
            );
        }

        String newPasswordHash =
                passwordHashingService.hashNewPassword(
                        changeRequest.newPassword()
                );
        try {
            return commitService.commit(
                    servletRequest,
                    targetUserId,
                    account.passwordHash(),
                    newPasswordHash,
                    auditContext
            ).map(LoginChallengeResponse::from)
                    .orElseGet(() -> reject(auditContext, targetUserId));
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.AUTH_UNAUTHENTICATED) {
                throw exception;
            }
            return reject(auditContext, targetUserId);
        }
    }

    private LoginChallengeResponse reject(
            AuthenticationRequestAuditContext auditContext,
            UUID targetUserId
    ) {
        auditService.recordInitialPasswordChangeFailedRequired(
                auditContext,
                targetUserId
        );
        throw new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                "인증 정보가 올바르지 않습니다."
        );
    }
}
