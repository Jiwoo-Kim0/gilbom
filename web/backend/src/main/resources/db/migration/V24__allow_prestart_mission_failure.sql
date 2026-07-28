-- Mission은 실제 순찰을 시작하기 전 준비 단계에서도 실패할 수 있다.
-- started_at은 실제 주행 시작 시각이므로 시작 전 실패에서는 NULL을 유지하고,
-- ended_at에는 실패가 확정된 시각을 기록한다.
ALTER TABLE robot.mission
    DROP CONSTRAINT ck_mission_lifecycle_times;

ALTER TABLE robot.mission
    ADD CONSTRAINT ck_mission_lifecycle_times CHECK (
        (
            status IN ('CREATED', 'READY')
            AND started_at IS NULL
            AND ended_at IS NULL
        )
        OR (
            status IN ('RUNNING', 'PAUSED')
            AND started_at IS NOT NULL
            AND ended_at IS NULL
        )
        OR (
            status = 'COMPLETED'
            AND started_at IS NOT NULL
            AND ended_at IS NOT NULL
            AND ended_at >= started_at
        )
        OR (
            status IN ('CANCELLED', 'FAILED')
            AND ended_at IS NOT NULL
            AND (
                started_at IS NULL
                OR ended_at >= started_at
            )
        )
    );

COMMENT ON CONSTRAINT ck_mission_lifecycle_times ON robot.mission IS
    '시작 전 CANCELLED/FAILED는 started_at NULL을 허용하고 종료 시각은 필수로 기록';
