package com.gilbom.report;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

@Service
public class ReportStateTransitionService {

    private static final Map<ReportStatus, Map<ReportAction, ReportStatus>>
            TRANSITIONS = transitions();

    public ReportStatus transition(
            ReportStatus current,
            ReportAction action
    ) {
        Map<ReportAction, ReportStatus> allowed = TRANSITIONS.get(current);
        ReportStatus target = allowed == null
                ? null
                : allowed.get(action);
        if (target == null) {
            throw new BusinessException(
                    ErrorCode.REPORT_STATE_CONFLICT);
        }
        return target;
    }

    private static Map<ReportStatus, Map<ReportAction, ReportStatus>>
            transitions() {
        Map<ReportStatus, Map<ReportAction, ReportStatus>> transitions =
                new EnumMap<>(ReportStatus.class);
        transitions.put(
                ReportStatus.DRAFT,
                Map.of(
                        ReportAction.UPDATE, ReportStatus.DRAFT,
                        ReportAction.SUBMIT, ReportStatus.IN_REVIEW,
                        ReportAction.DELETE, ReportStatus.DRAFT));
        transitions.put(
                ReportStatus.IN_REVIEW,
                Map.of(
                        ReportAction.APPROVE, ReportStatus.APPROVED,
                        ReportAction.REJECT, ReportStatus.REJECTED));
        transitions.put(
                ReportStatus.REJECTED,
                Map.of(
                        ReportAction.UPDATE, ReportStatus.DRAFT,
                        ReportAction.DELETE, ReportStatus.REJECTED));
        transitions.put(ReportStatus.APPROVED, Map.of());
        return Map.copyOf(transitions);
    }
}
