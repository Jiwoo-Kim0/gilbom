-- 실행 전 CREATED Mission만 관리자 화면에서 삭제할 수 있도록 DB 경계를 연다.
-- Mission 시작 이후의 상태·Telemetry·Event·Command 이력은 계속 보존한다.

CREATE FUNCTION robot.enforce_created_mission_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, robot
AS $$
BEGIN
    IF OLD.status <> 'CREATED'
            OR OLD.started_at IS NOT NULL
            OR OLD.ended_at IS NOT NULL
            OR OLD.progress_percent <> 0 THEN
        RAISE EXCEPTION 'only untouched CREATED mission can be deleted'
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END;
$$;

REVOKE ALL
    ON FUNCTION robot.enforce_created_mission_delete()
    FROM PUBLIC;

CREATE TRIGGER tr_mission_created_delete_only
    BEFORE DELETE ON robot.mission
    FOR EACH ROW
    EXECUTE FUNCTION robot.enforce_created_mission_delete();

CREATE OR REPLACE FUNCTION robot.reject_mission_status_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, robot
AS $$
BEGIN
    IF TG_OP = 'DELETE'
            AND OLD.before_status IS NULL
            AND OLD.after_status = 'CREATED'
            AND OLD.source_event_id IS NULL
            AND EXISTS (
                SELECT 1
                FROM robot.mission mission
                WHERE mission.mission_id = OLD.mission_id
                  AND mission.status = 'CREATED'
                  AND mission.started_at IS NULL
                  AND mission.ended_at IS NULL
                  AND mission.progress_percent = 0
            )
            AND NOT EXISTS (
                SELECT 1
                FROM robot.mission_status_history history
                WHERE history.mission_id = OLD.mission_id
                  AND history.history_id <> OLD.history_id
            ) THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION 'mission_status_history is append-only'
        USING ERRCODE = '55000';
END;
$$;

GRANT DELETE
    ON robot.mission,
       robot.mission_status_history
    TO "${applicationRole}";

COMMENT ON FUNCTION robot.enforce_created_mission_delete() IS
    '시작·진행 이력이 없는 CREATED Mission만 물리 삭제하도록 강제';
