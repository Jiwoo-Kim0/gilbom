-- 통계 API는 병합된 원본을 제외한 현재 결함만 last_seen_at 기간으로 읽는다.
-- V12의 ix_defect_workflow_filter는 workflow/type/risk가 선두라 기간만 좁히는
-- 조회에서 전체 인덱스 범위를 더 많이 읽고, 지역 조건도 Filter로 남는다.
--
-- 전체 기간 통계와 지역별 기간 통계를 분리해 두 access path를 모두 지원한다.
-- 집계와 권한 범위 판정에 필요한 칼럼은 key 또는 INCLUDE로 보관해 heap 접근을
-- 줄일 수 있게 하되, 실제 Index Only Scan 여부는 visibility map에 따라 달라진다.

CREATE INDEX ix_defect_current_last_seen_stats
    ON defect.defect (
        last_seen_at DESC,
        defect_id
    )
    INCLUDE (
        patrol_area_id,
        defect_type,
        workflow_status
    )
    WHERE merged_into_defect_id IS NULL;

CREATE INDEX ix_defect_current_area_last_seen_stats
    ON defect.defect (
        patrol_area_id,
        last_seen_at DESC,
        defect_id
    )
    INCLUDE (
        defect_type,
        workflow_status
    )
    WHERE merged_into_defect_id IS NULL;

COMMENT ON INDEX defect.ix_defect_current_last_seen_stats IS
    '현재 결함의 UTC 기간별 상태·유형·날짜 통계 access path';

COMMENT ON INDEX defect.ix_defect_current_area_last_seen_stats IS
    '현재 결함의 순찰 구역·UTC 기간 통계 access path';
