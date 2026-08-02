#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
work_dir="$(mktemp -d)"
cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT HUP INT TERM

valid="$work_dir/valid.json"
jq -n '
  {
    schemaVersion: 1,
    status: "PASS",
    isolation: {
      productionCredentialsUsed: false,
      productionHostContacted: false,
      dockerSocketMounted: false,
      dindTls: true
    },
    scenarios: {
      noMigrationAutomaticRollback: {
        scenario: "A_NO_MIGRATION",
        status: "AUTOMATIC_ROLLBACK_SUCCEEDED",
        flyway: {
          beforeVersion: 35,
          afterFailureVersion: 35,
          changed: false
        },
        previousArtifactReload: "PASS",
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      },
      compatibleMigrationApprovalGate: {
        scenario: "B_COMPATIBLE_MIGRATION",
        status: "MANUALLY_APPROVED_ROLLBACK_SUCCEEDED",
        unapprovedRollbackBlocked: true,
        flyway: {
          beforeVersion: 35,
          afterFailureVersion: 36,
          afterRollbackVersion: 36
        },
        approval: {
          compatibility: "APPROVED_PREVIOUS_BACKEND_COMPATIBLE",
          preflightSha256:
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          approvedBy: "rollback-drill-system"
        },
        previousArtifactReload: "PASS",
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      },
      incompatibleMigrationContainment: {
        scenario: "C_INCOMPATIBLE_MIGRATION",
        status: "MANUAL_RECOVERY_REQUIRED",
        compatibility: "INCOMPATIBLE_PREVIOUS_BACKEND",
        automaticRollbackAllowed: false,
        flyway: {
          beforeVersion: 35,
          afterFailureVersion: 37,
          changed: true
        },
        services: {
          frontend: "STOPPED",
          candidateBackend: "PRESERVED",
          database: "PRESERVED"
        },
        volumesPreserved: "PASS",
        migrationUndoPerformed: false,
        backupRestorePerformed: false
      }
    },
    requiredEvidence: {
      previousArtifactReload: "PASS",
      databaseVolumePersistence: "PASS",
      robotImageVolumePersistence: "PASS",
      reportPdfVolumePersistence: "PASS"
    }
  }
' > "$valid"

sh "$SCRIPT_DIR/verify-production-rollback-drill-evidence.sh" \
  "$valid" >/dev/null

jq \
  '.scenarios.incompatibleMigrationContainment.automaticRollbackAllowed = true' \
  "$valid" > "$work_dir/unsafe.json"
if sh "$SCRIPT_DIR/verify-production-rollback-drill-evidence.sh" \
    "$work_dir/unsafe.json" >/dev/null 2>&1; then
  echo "Unsafe incompatible rollback evidence was accepted" >&2
  exit 1
fi

jq \
  '.scenarios.noMigrationAutomaticRollback.migrationUndoPerformed = true' \
  "$valid" > "$work_dir/undo.json"
if sh "$SCRIPT_DIR/verify-production-rollback-drill-evidence.sh" \
    "$work_dir/undo.json" >/dev/null 2>&1; then
  echo "Migration undo evidence was accepted" >&2
  exit 1
fi

echo "Production rollback drill evidence self-test: PASS"
