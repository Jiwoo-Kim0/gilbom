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
    public static final String APPROVE = "REPORT_APPROVE";

    private final AuthenticationUserRepository userRepository;
    private final ReportRepository reportRepository;

    public ReportAccessService(
            AuthenticationUserRepository userRepository,
            ReportRepository reportRepository
    ) {
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
    }

    @Transactional(readOnly = true)
    public void require(UUID userId, String permission) {
        if (!hasPermission(userId, permission)) {
            throw new BusinessException(
                    ErrorCode.REPORT_ACCESS_DENIED);
        }
    }

    /**
     * 권한을 먼저 확인한 뒤 현재 보고서 객체 범위를 검사한다.
     *
     * <p>현재 정책은 모든 활성 보고서가 권한 보유자의 범위에 속한다. 이
     * 진입점을 통해 향후 구역·조직 배정 범위를 추가해도 command 호출부와
     * 권한/존재 확인 순서를 유지한다.</p>
     */
    @Transactional(readOnly = true)
    public void requireReport(
            UUID userId,
            UUID reportId,
            String permission
    ) {
        require(userId, permission);
        if (reportId == null
                || reportRepository.findActiveById(reportId)
                        .isEmpty()) {
            throw new BusinessException(
                    ErrorCode.REPORT_NOT_FOUND);
        }
    }

    /**
     * SSE 이벤트를 보내기 직전의 현재 권한·객체 범위 검사다.
     *
     * <p>삭제 이벤트도 구독자 화면에서 제거해야 하므로 soft-delete된 보고서까지
     * 존재 여부를 확인한다. 현재 MVP 범위는 REPORT_VIEW 권한 전체지만 향후 조직·
     * 구역 범위가 추가되면 이 진입점에서 함께 제한한다.</p>
     */
    @Transactional(readOnly = true)
    public boolean canReceiveEvent(UUID userId, UUID reportId) {
        return reportId != null
                && hasPermission(userId, VIEW)
                && reportRepository.findById(reportId).isPresent();
    }

    private boolean hasPermission(UUID userId, String permission) {
        return userId != null
                && permission != null
                && userRepository.findActiveUser(userId)
                        .map(user -> user.permissions()
                                .contains(permission))
                        .orElse(false);
    }
}
