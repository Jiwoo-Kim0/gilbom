package com.gilbom.report.realtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.gilbom.report.ReportRecord;
import com.gilbom.report.ReportStatus;

/**
 * SSE를 REST 재조회 신호로만 쓰기 위한 최소 보고서 변경 이벤트다.
 *
 * <p>제목·본문·통계·작성자·승인자 정보는 싣지 않는다. 클라이언트는 이 값으로
 * 화면 상태를 확정하지 않고 대상 REST API를 다시 조회한다.</p>
 */
public record ReportRealtimeEvent(
        UUID reportId,
        ReportStatus reportStatus,
        int currentVersionNo,
        boolean deleted,
        Instant occurredAt
) {

    public static final String EVENT_NAME = "report.updated";

    public ReportRealtimeEvent {
        Objects.requireNonNull(reportId, "reportId must not be null");
        Objects.requireNonNull(
                reportStatus,
                "reportStatus must not be null");
        Objects.requireNonNull(
                occurredAt,
                "occurredAt must not be null");
        if (currentVersionNo < 1) {
            throw new IllegalArgumentException(
                    "currentVersionNo must be positive");
        }
    }

    public static ReportRealtimeEvent from(ReportRecord report) {
        Objects.requireNonNull(report, "report must not be null");
        return new ReportRealtimeEvent(
                report.reportId(),
                report.status(),
                report.currentVersionNo(),
                report.deletedAt() != null,
                report.updatedAt());
    }
}
