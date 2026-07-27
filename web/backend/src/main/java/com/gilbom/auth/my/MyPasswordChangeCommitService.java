package com.gilbom.auth.my;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.session.AuthenticationSessionService;

@Service
public class MyPasswordChangeCommitService {

    private final MyAccountRepository accountRepository;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationAuditService auditService;

    public MyPasswordChangeCommitService(
            MyAccountRepository accountRepository,
            AuthenticationSessionService sessionService,
            AuthenticationAuditService auditService
    ) {
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * hash 교체, 필수 감사와 모든 서버 세션 삭제를 한 DB 트랜잭션으로 확정한다.
     */
    @Transactional
    public boolean commit(
            UUID userId,
            String expectedCurrentPasswordHash,
            String newPasswordHash,
            AuthenticationRequestAuditContext auditContext
    ) {
        if (accountRepository.updatePassword(
                userId,
                expectedCurrentPasswordHash,
                newPasswordHash
        ) != 1) {
            return false;
        }

        auditService.recordPasswordChangedRequired(
                auditContext,
                userId
        );
        int revokedSessions =
                sessionService.invalidateAllUserSessions(userId);
        if (revokedSessions < 1) {
            throw new IllegalStateException(
                    "Password change did not revoke the current session"
            );
        }
        auditService.recordSessionRevokedRequired(
                auditContext,
                userId,
                userId
        );
        return true;
    }
}
