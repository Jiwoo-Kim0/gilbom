package com.gilbom.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class ReportStateTransitionServiceTest {

    private final ReportStateTransitionService service =
            new ReportStateTransitionService();

    @Test
    void allowsEveryContractedTransition() {
        Map<Transition, ReportStatus> allowed = Map.of(
                new Transition(
                        ReportStatus.DRAFT,
                        ReportAction.UPDATE),
                ReportStatus.DRAFT,
                new Transition(
                        ReportStatus.DRAFT,
                        ReportAction.SUBMIT),
                ReportStatus.IN_REVIEW,
                new Transition(
                        ReportStatus.DRAFT,
                        ReportAction.DELETE),
                ReportStatus.DRAFT,
                new Transition(
                        ReportStatus.IN_REVIEW,
                        ReportAction.APPROVE),
                ReportStatus.APPROVED,
                new Transition(
                        ReportStatus.IN_REVIEW,
                        ReportAction.REJECT),
                ReportStatus.REJECTED,
                new Transition(
                        ReportStatus.REJECTED,
                        ReportAction.UPDATE),
                ReportStatus.DRAFT,
                new Transition(
                        ReportStatus.REJECTED,
                        ReportAction.DELETE),
                ReportStatus.REJECTED);

        allowed.forEach((transition, expected) ->
                assertThat(service.transition(
                        transition.status(),
                        transition.action()))
                        .isEqualTo(expected));
    }

    @Test
    void rejectsEveryTransitionMissingFromTheContract() {
        for (ReportStatus status : ReportStatus.values()) {
            for (ReportAction action : ReportAction.values()) {
                if (isAllowed(status, action)) {
                    continue;
                }
                assertThatThrownBy(() ->
                        service.transition(status, action))
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                exception -> assertThat(
                                        exception.errorCode())
                                        .isEqualTo(
                                                ErrorCode
                                                        .REPORT_STATE_CONFLICT));
            }
        }
    }

    private static boolean isAllowed(
            ReportStatus status,
            ReportAction action
    ) {
        return switch (status) {
            case DRAFT -> action == ReportAction.UPDATE
                    || action == ReportAction.SUBMIT
                    || action == ReportAction.DELETE;
            case IN_REVIEW -> action == ReportAction.APPROVE
                    || action == ReportAction.REJECT;
            case REJECTED -> action == ReportAction.UPDATE
                    || action == ReportAction.DELETE;
            case APPROVED -> false;
        };
    }

    private record Transition(
            ReportStatus status,
            ReportAction action
    ) {
    }
}
