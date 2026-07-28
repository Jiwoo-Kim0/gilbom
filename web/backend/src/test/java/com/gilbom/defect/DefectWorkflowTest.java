package com.gilbom.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.gilbom.common.error.BusinessException;
import com.gilbom.common.error.ErrorCode;

class DefectWorkflowTest {

    @ParameterizedTest
    @CsvSource({
            "NEW,ACCEPTED",
            "NEW,FALSE_POSITIVE",
            "NEW,REJECTED",
            "ACCEPTED,IN_PROGRESS",
            "ACCEPTED,FALSE_POSITIVE",
            "ACCEPTED,REJECTED",
            "IN_PROGRESS,RESOLVED",
            "IN_PROGRESS,FALSE_POSITIVE",
            "IN_PROGRESS,REJECTED"
    })
    void allowsDocumentedTransitions(String from, String to) {
        assertThatCode(() -> DefectWorkflow.requireTransitionAllowed(from, to))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            // 단계를 건너뛰는 전이는 허용하지 않는다.
            "NEW,IN_PROGRESS",
            "NEW,RESOLVED",
            "ACCEPTED,RESOLVED",
            // 종결 상태에서는 어떤 전이도 나가지 않는다.
            "RESOLVED,IN_PROGRESS",
            "RESOLVED,ACCEPTED",
            "FALSE_POSITIVE,ACCEPTED",
            "REJECTED,NEW",
            // 되돌리는 전이도 막는다.
            "IN_PROGRESS,ACCEPTED",
            "ACCEPTED,NEW"
    })
    void rejectsUndocumentedTransitionsAsConflict(String from, String to) {
        assertThatThrownBy(() ->
                DefectWorkflow.requireTransitionAllowed(from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.DEFECT_STATE_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "NEW", "ACCEPTED", "IN_PROGRESS",
            "RESOLVED", "FALSE_POSITIVE", "REJECTED"
    })
    void rejectsTransitionToSameStatus(String status) {
        assertThatThrownBy(() ->
                DefectWorkflow.requireTransitionAllowed(status, status))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 요청한");
    }

    @Test
    void normalizesAndValidatesStatusValues() {
        assertThat(DefectWorkflow.requireKnownStatus(" in_progress "))
                .isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() ->
                DefectWorkflow.requireKnownStatus("ARCHIVED"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_INVALID_REQUEST);
    }

    @Test
    void treatsBlankResolutionAsAbsent() {
        assertThat(DefectWorkflow.requireKnownResolution("  ")).isNull();
        assertThat(DefectWorkflow.requireKnownResolution("repaired"))
                .isEqualTo("REPAIRED");
        assertThatThrownBy(() ->
                DefectWorkflow.requireKnownResolution("PARTIALLY_FIXED"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void requiresResolutionWhenClosingAsResolved() {
        assertThatThrownBy(() -> DefectWorkflow.requireResolutionConsistent(
                "RESOLVED",
                null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_BUSINESS_RULE_VIOLATION);

        assertThatCode(() -> DefectWorkflow.requireResolutionConsistent(
                "RESOLVED",
                "REPAIRED"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsResolutionOnNonTerminalStatus() {
        assertThatThrownBy(() -> DefectWorkflow.requireResolutionConsistent(
                "IN_PROGRESS",
                "REPAIRED"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.COMMON_BUSINESS_RULE_VIOLATION);
    }

    @Test
    void allowsTerminalStatusesWithoutResolutionExceptResolved() {
        assertThatCode(() -> DefectWorkflow.requireResolutionConsistent(
                "FALSE_POSITIVE",
                null))
                .doesNotThrowAnyException();
        assertThatCode(() -> DefectWorkflow.requireResolutionConsistent(
                "REJECTED",
                "NOT_APPLICABLE"))
                .doesNotThrowAnyException();
    }

    @Test
    void marksOnlyClosedStatusesAsTerminal() {
        assertThat(DefectWorkflow.isTerminal("RESOLVED")).isTrue();
        assertThat(DefectWorkflow.isTerminal("FALSE_POSITIVE")).isTrue();
        assertThat(DefectWorkflow.isTerminal("REJECTED")).isTrue();
        assertThat(DefectWorkflow.isTerminal("NEW")).isFalse();
        assertThat(DefectWorkflow.isTerminal("ACCEPTED")).isFalse();
        assertThat(DefectWorkflow.isTerminal("IN_PROGRESS")).isFalse();
    }
}
