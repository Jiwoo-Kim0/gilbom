package com.gilbom.report;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gilbom.auth.mfa.AuthenticationUserRepository;
import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

/**
 * Reports currently have permission-wide global object scope. The policy is
 * centralized here so a future area/report assignment can narrow it without
 * changing every controller and query.
 */
@Service
public class ReportAccessService {

    public static final String VIEW = "REPORT_VIEW";
    public static final String CREATE = "REPORT_CREATE";
    public static final String UPDATE = "REPORT_UPDATE";

    private final AuthenticationUserRepository userRepository;

    public ReportAccessService(
            AuthenticationUserRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public void require(UUID userId, String permission) {
        boolean allowed = userId != null
                && userRepository.findActiveUser(userId)
                        .map(user -> user.permissions()
                                .contains(permission))
                        .orElse(false);
        if (!allowed) {
            throw new BusinessException(
                    ErrorCode.REPORT_ACCESS_DENIED);
        }
    }
}
