package com.gilbom.auth.my;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.gilbom.auth.audit.AuthenticationAuditService;
import com.gilbom.auth.audit.AuthenticationRequestAuditContext;
import com.gilbom.auth.my.MyAccountRepository.PasswordAccount;
import com.gilbom.auth.password.PasswordHashingService;
import com.gilbom.auth.session.AuthenticationCookieService;
import com.gilbom.auth.session.AuthenticationSessionService;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public class MyPasswordChangeService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MyPasswordChangeService.class);
    private static final String INVALID_CREDENTIALS_MESSAGE =
            "인증 정보가 올바르지 않습니다.";

    private final CurrentAccountService currentAccountService;
    private final MyAccountRepository accountRepository;
    private final PasswordHashingService passwordHashingService;
    private final AuthenticationAuditService auditService;
    private final MyPasswordChangeCommitService commitService;
    private final AuthenticationSessionService sessionService;
    private final AuthenticationCookieService cookieService;

    public MyPasswordChangeService(
            CurrentAccountService currentAccountService,
            MyAccountRepository accountRepository,
            PasswordHashingService passwordHashingService,
            AuthenticationAuditService auditService,
            MyPasswordChangeCommitService commitService,
            AuthenticationSessionService sessionService,
            AuthenticationCookieService cookieService
    ) {
        this.currentAccountService = currentAccountService;
        this.accountRepository = accountRepository;
        this.passwordHashingService = passwordHashingService;
        this.auditService = auditService;
        this.commitService = commitService;
        this.sessionService = sessionService;
        this.cookieService = cookieService;
    }

    public void change(
            MyPasswordChangeRequest changeRequest,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID userId = currentAccountService.requireAuthenticatedSession(
                authentication,
                request
        );
        AuthenticationRequestAuditContext auditContext =
                AuthenticationRequestAuditContext.from(request);
        PasswordAccount account =
                accountRepository.findPasswordAccount(userId)
                        .orElseGet(() -> reject(
                                auditContext,
                                userId
                        ));

        boolean currentPasswordMatches = verifySafely(
                changeRequest.currentPassword(),
                account.passwordHash(),
                auditContext
        );
        if (!currentPasswordMatches) {
            reject(auditContext, userId);
        }

        boolean reusesCurrentPassword = verifySafely(
                changeRequest.newPassword(),
                account.passwordHash(),
                auditContext
        );
        if (reusesCurrentPassword) {
            auditService.recordPasswordChangeFailedRequired(
                    auditContext,
                    userId
            );
            throw new BusinessException(
                    ErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED
            );
        }

        String newPasswordHash = hashSafely(
                changeRequest.newPassword(),
                auditContext
        );
        if (!commitService.commit(
                userId,
                account.passwordHash(),
                newPasswordHash,
                auditContext
        )) {
            reject(auditContext, userId);
        }

        // DB에서 모든 세션 삭제가 커밋된 뒤 현재 요청 객체와 브라우저 쿠키도 끝낸다.
        sessionService.expireCurrentClientSession(request);
        cookieService.expire(response);
    }

    private boolean verifySafely(
            String rawPassword,
            String storedHash,
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return passwordHashingService.verifyAgainstNullableHash(
                    rawPassword,
                    storedHash
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Password change verification failed safely. "
                            + "requestId={} category=PASSWORD_HASH_CONFIGURATION",
                    auditContext.requestId()
            );
            return false;
        }
    }

    private String hashSafely(
            String rawPassword,
            AuthenticationRequestAuditContext auditContext
    ) {
        try {
            return passwordHashingService.hashNewPassword(rawPassword);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Password change hashing failed safely. "
                            + "requestId={} category=PASSWORD_HASH_CONFIGURATION",
                    auditContext.requestId()
            );
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        }
    }

    private <T> T reject(
            AuthenticationRequestAuditContext auditContext,
            UUID userId
    ) {
        auditService.recordPasswordChangeFailedRequired(
                auditContext,
                userId
        );
        throw new BusinessException(
                ErrorCode.AUTH_UNAUTHENTICATED,
                INVALID_CREDENTIALS_MESSAGE
        );
    }
}
