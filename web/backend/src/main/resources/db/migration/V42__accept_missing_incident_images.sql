-- Robot AI가 점자블록 파손(DAMAGED)뿐 아니라 미설치(MISSING) 판단과 증거
-- 이미지를 같은 Incident 파이프라인으로 보낼 수 있게 한다.
--
-- 미설치는 검출할 객체가 없으므로 AI 점수와 bbox가 없을 수 있다. 대표 결함의
-- latest_confidence는 이미 nullable이며, 관측과 위치 검토 대기열의 confidence만
-- nullable로 넓힌다. 기존 행과 이미지는 변경하지 않는다.

ALTER TABLE defect.defect
    DROP CONSTRAINT ck_defect_type,
    ADD CONSTRAINT ck_defect_type CHECK (defect_type IN (
        'DAMAGED', 'MISSING', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
    ));

ALTER TABLE defect.defect_observation
    DROP CONSTRAINT ck_observation_defect_type,
    ADD CONSTRAINT ck_observation_defect_type CHECK (
        detected_defect_type IN (
            'DAMAGED', 'MISSING', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
        )
    ),
    ALTER COLUMN confidence DROP NOT NULL;

ALTER TABLE defect.incident_ingestion_issue
    DROP CONSTRAINT ck_incident_ingestion_issue_defect_type,
    ADD CONSTRAINT ck_incident_ingestion_issue_defect_type CHECK (
        detected_defect_type IN (
            'DAMAGED', 'MISSING', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
        )
    ),
    ALTER COLUMN confidence DROP NOT NULL;

COMMENT ON COLUMN defect.defect_observation.confidence IS
    'AI 판정 신뢰도. MISSING처럼 점수를 제공하지 않는 판단은 NULL';
COMMENT ON COLUMN defect.incident_ingestion_issue.confidence IS
    '검토 대기 Incident의 AI 판정 신뢰도. 미제공 시 NULL';
