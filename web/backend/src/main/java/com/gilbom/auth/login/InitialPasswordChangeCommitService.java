package com.gilbom.auth.login;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.model.AuthenticationState;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.auth.session.AuthenticationSessionService.ChallengeSession;
import com.gilbom.auth.session.SessionSecurityState;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class InitialPasswordChangeCommitService {

    private final LoginAccountRepository accountRepository;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationAuditService auditService;

    public InitialPasswordChangeCommitService(
            LoginAccountRepository accountRepository,
            AuthenticationSessionService sessionService,
            AuthenticationAuditService auditService
    ) {
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * 계정 갱신, 다른 세션 폐기, 성공 감사, 상태 전이를 하나의 DB 트랜잭션으로 묶는다.
     */
    @Transactional
    public Optional<ChallengeSession> commit(
            HttpServletRequest request,
            UUID userId,
            String expectedCurrentPasswordHash,
            String newPasswordHash,
            AuthenticationRequestAuditContext auditContext
    ) {
        Optional<SessionSecurityState> state =
                sessionService.findCurrentState(request);
        if (state.isEmpty()
                || !state.get().belongsTo(userId)
                || state.get().authenticationState()
                    != AuthenticationState.PASSWORD_CHANGE_REQUIRED) {
            return Optional.empty();
        }

        if (accountRepository.updateInitialPassword(
                userId,
                expectedCurrentPasswordHash,
                newPasswordHash
        ) != 1) {
            return Optional.empty();
        }

        sessionService.invalidateOtherUserSessions(userId, request);
        auditService.recordInitialPasswordChangedRequired(
                auditContext,
                userId
        );
        return Optional.of(sessionService.continueAfterInitialPasswordChange(
                request,
                userId
        ));
    }
}
