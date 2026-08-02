-- 결함 유형을 3종으로 좁힌다: DAMAGED, INSTALLATION_INVALID, UNKNOWN_ANOMALY.
--
-- 제품에서 쓰지 않기로 한 CONTAMINATED, UPLIFTED, BLOCKED, MISSING_SUSPECTED를
-- 뺀다. MQTT 계약(IncidentEventPayload.DefectType)은 원래 DAMAGED만 받으므로
-- 로봇 쪽 계약 변경은 없다. 나머지 두 종은 사람이 결함 수정에서 지정한다.
--
-- 없어지는 값이 들어 있는 행은 '기타 이상'으로 옮긴다. 지우지 않는 이유는 관측·
-- 이미지·이력이 딸려 있어서다. 무엇이었는지는 아래 이력으로 남긴다.

UPDATE defect.defect
SET defect_type = 'UNKNOWN_ANOMALY',
    updated_at = NOW()
WHERE defect_type IN (
    'CONTAMINATED', 'UPLIFTED', 'BLOCKED', 'MISSING_SUSPECTED'
);

UPDATE defect.defect_observation
SET detected_defect_type = 'UNKNOWN_ANOMALY'
WHERE detected_defect_type IN (
    'CONTAMINATED', 'UPLIFTED', 'BLOCKED', 'MISSING_SUSPECTED'
);

UPDATE defect.incident_ingestion_issue
SET detected_defect_type = 'UNKNOWN_ANOMALY'
WHERE detected_defect_type IN (
    'CONTAMINATED', 'UPLIFTED', 'BLOCKED', 'MISSING_SUSPECTED'
);

ALTER TABLE defect.defect
    DROP CONSTRAINT ck_defect_type,
    ADD CONSTRAINT ck_defect_type CHECK (defect_type IN (
        'DAMAGED', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
    ));

ALTER TABLE defect.defect_observation
    DROP CONSTRAINT ck_observation_defect_type,
    ADD CONSTRAINT ck_observation_defect_type CHECK (
        detected_defect_type IN (
            'DAMAGED', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
        )
    );

ALTER TABLE defect.incident_ingestion_issue
    DROP CONSTRAINT ck_incident_ingestion_issue_defect_type,
    ADD CONSTRAINT ck_incident_ingestion_issue_defect_type CHECK (
        detected_defect_type IN (
            'DAMAGED', 'INSTALLATION_INVALID', 'UNKNOWN_ANOMALY'
        )
    );

-- 과거 이력(defect_status_history의 before/after JSONB, 감사 로그 스냅샷)은
-- 그대로 둔다. 그 시점에 실제로 그 값이었다는 기록이고, 되짚을 때 필요하다.
