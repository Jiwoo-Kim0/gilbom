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

    /**
     * 권장 흐름(NEW → ACCEPTED → IN_PROGRESS → RESOLVED)대로 가는 전이.
     */
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

    /**
     * 전이는 단계에 묶이지 않는다. 현장 처리가 순서대로 흐르지 않기 때문이다.
     * 접수 없이 바로 완료하거나, 완료한 뒤 재작업으로 되돌리는 건이 있다.
     */
    @ParameterizedTest
    @CsvSource({
            // 단계를 건너뛴다.
            "NEW,IN_PROGRESS",
            "NEW,RESOLVED",
            "ACCEPTED,RESOLVED",
            // 종결에서 되살린다.
            "RESOLVED,IN_PROGRESS",
            "RESOLVED,ACCEPTED",
            "FALSE_POSITIVE,ACCEPTED",
            "REJECTED,IN_PROGRESS",
            // 종결끼리 옮긴다.
            "RESOLVED,FALSE_POSITIVE",
            "REJECTED,RESOLVED",
            // 앞 단계로 되돌린다.
            "IN_PROGRESS,ACCEPTED"
    })
    void allowsTransitionsThatSkipOrRewindStages(String from, String to) {
        assertThatCode(() -> DefectWorkflow.requireTransitionAllowed(from, to))
                .doesNotThrowAnyException();
    }

    /**
     * NEW는 적재 시점에만 붙는다. 사람이 "아직 안 본 것"으로 되돌리면 관측 이력과
     * 어긋나므로 이것만 막는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ACCEPTED", "IN_PROGRESS",
            "RESOLVED", "FALSE_POSITIVE", "REJECTED"
    })
    void rejectsReturningToNewAsConflict(String from) {
        assertThatThrownBy(() ->
                DefectWorkflow.requireTransitionAllowed(from, "NEW"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("신규 상태로는")
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

    /**
     * 종결 표식은 이제 "전이가 끝났다"가 아니라 "처리 결과를 가질 수 있고 내용 수정이
     * 막힌다"만 뜻한다. 전이는 {@code allowsTransitionsThatSkipOrRewindStages} 참고.
     */
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
