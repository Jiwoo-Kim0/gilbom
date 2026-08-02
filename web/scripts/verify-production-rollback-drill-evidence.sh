#!/bin/sh

set -eu

fail() {
  echo "Production rollback drill evidence verification failed: $1" >&2
  exit 1
}

[ "$#" -eq 1 ] \
  || fail "usage: $0 SUMMARY_JSON"
summary="$1"
[ -f "$summary" ] && [ ! -L "$summary" ] && [ -s "$summary" ] \
  || fail "summary is missing, empty, or symlinked"

jq -e '
  .schemaVersion == 1
  and .status == "PASS"
  and .isolation.productionCredentialsUsed == false
  and .isolation.productionHostContacted == false
  and .isolation.dockerSocketMounted == false
  and .isolation.dindTls == true

  and .scenarios.noMigrationAutomaticRollback.scenario
    == "A_NO_MIGRATION"
  and .scenarios.noMigrationAutomaticRollback.status
    == "AUTOMATIC_ROLLBACK_SUCCEEDED"
  and .scenarios.noMigrationAutomaticRollback.flyway.changed == false
  and .scenarios.noMigrationAutomaticRollback.flyway.beforeVersion
    == .scenarios.noMigrationAutomaticRollback.flyway.afterFailureVersion
  and .scenarios.noMigrationAutomaticRollback.previousArtifactReload
    == "PASS"
  and .scenarios.noMigrationAutomaticRollback.volumesPreserved == "PASS"

  and .scenarios.compatibleMigrationApprovalGate.scenario
    == "B_COMPATIBLE_MIGRATION"
  and .scenarios.compatibleMigrationApprovalGate.status
    == "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED"
  and .scenarios.compatibleMigrationApprovalGate.unapprovedRollbackBlocked
    == true
  and .scenarios.compatibleMigrationApprovalGate.flyway.beforeVersion
    < .scenarios.compatibleMigrationApprovalGate.flyway.afterFailureVersion
  and .scenarios.compatibleMigrationApprovalGate.flyway.afterRollbackVersion
    == .scenarios.compatibleMigrationApprovalGate.flyway.afterFailureVersion
  and .scenarios.compatibleMigrationApprovalGate.approval.compatibility
    == "APPROVED_PREVIOUS_BACKEND_COMPATIBLE"
  and (
    .scenarios.compatibleMigrationApprovalGate.approval.preflightSha256
    | test("^[0-9a-f]{64}$")
  )
  and (
    .scenarios.compatibleMigrationApprovalGate.approval.approvedBy
    | type == "string" and length > 0
  )
  and .scenarios.compatibleMigrationApprovalGate.previousArtifactReload
    == "PASS"
  and .scenarios.compatibleMigrationApprovalGate.volumesPreserved == "PASS"

  and .scenarios.incompatibleMigrationContainment.scenario
    == "C_INCOMPATIBLE_MIGRATION"
  and .scenarios.incompatibleMigrationContainment.status
    == "MANUAL_RECOVERY_REQUIRED"
  and .scenarios.incompatibleMigrationContainment.compatibility
    == "INCOMPATIBLE_PREVIOUS_BACKEND"
  and .scenarios.incompatibleMigrationContainment.automaticRollbackAllowed
    == false
  and .scenarios.incompatibleMigrationContainment.flyway.beforeVersion
    < .scenarios.incompatibleMigrationContainment.flyway.afterFailureVersion
  and .scenarios.incompatibleMigrationContainment.services.frontend
    == "STOPPED"
  and .scenarios.incompatibleMigrationContainment.services.candidateBackend
    == "PRESERVED"
  and .scenarios.incompatibleMigrationContainment.services.database
    == "PRESERVED"
  and .scenarios.incompatibleMigrationContainment.volumesPreserved == "PASS"

  and all(
    [
      .scenarios.noMigrationAutomaticRollback,
      .scenarios.compatibleMigrationApprovalGate,
      .scenarios.incompatibleMigrationContainment
    ][];
    .migrationUndoPerformed == false
    and .backupRestorePerformed == false
  )

  and .requiredEvidence.previousArtifactReload == "PASS"
  and .requiredEvidence.databaseVolumePersistence == "PASS"
  and .requiredEvidence.robotImageVolumePersistence == "PASS"
  and .requiredEvidence.reportPdfVolumePersistence == "PASS"
' "$summary" >/dev/null \
  || fail "summary does not satisfy the complete A/B/C drill contract"

echo "Production rollback drill evidence verification: PASS"
