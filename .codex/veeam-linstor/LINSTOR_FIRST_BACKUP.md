# First live LINSTOR VBR backup — 2026-10-31

Outcome: **FAILED**, no validated backup/restore. User deployed the packages;
enabled `kvm.backup.linstor.full.enabled=true` for lab zone
`af95c50a-a7fc-4c3c-a098-cb348ed123e1` through updateConfiguration after
finding it disabled. Global default remains false. Discovery then exposed
LINSTOR-POC without NFS connection metadata.

Job `linstor-test`, policy `7bff101a-285a-48fc-826f-fcad063b6ce8`, session
`d89eb815-b6a7-41d5-bfae-a5485034b413`, VM task
`22f6018f-a933-434d-9820-e08c7ddaafac`, VM `linstor-validation`
(`3b89203c-2667-4c76-9e10-4d85583e30d3`, CloudStack 3).
Worker update failed but snapshot rollback/deletion and reconnection succeeded;
preparation resumed with a warning. VBR logged Raw, no incremental capability,
checkpoint N/A and no to-checkpoint; it attempted a full disk backup.

## Primary failure: export larger than advertised disk

CloudStack backup `5c948b15-1096-4522-b2c7-85d1ebc8c5eb` (3) created at
07:57:16 UTC. Native snapshot and clone
`cs-vbr-5c948b1510964522b2c785d1ebc8c5eb`, read-only NBD and HTTPS image
registration succeeded on host1. Transfer: `191c27dd-de96-408e-bcaf-a7b2e595bfbe`.

VBR rejected an extent at offset 0 with size **8592252928**, beyond advertised
disk size **8589934592**. Source and clone blockdev sizes both matched the
larger value. This was backend padding exposed by an unbounded raw export;
VBR failed before successful disk reading. Final task result at 08:04:29 UTC:
Failed. Worker-update warnings and later disconnect errors were not the primary cause.

Local fix: persist the logical `StorageBackupVolume.sizeBytes`; use a bounded
raw image view (`driver=raw,file.driver=host_device,...,size=<logical bytes>`)
for native download and new-target upload. Missing/invalid sizes cannot start
exports. Old session metadata still permits cleanup. NFS construction is unchanged.

Read-only qemu-img inspection confirmed the deployed QEMU supports this raw
host_device size option. A separate disposable sparse-file NBD probe on host1
confirmed the larger backing file exports exactly 8 GiB read-only, extents stay
within that boundary and reads beyond it fail. The probe process/file were removed;
it did not export the live VM's source disk.

## Secondary failure: missing snapshot-definition ownership

Transfer finalization/NBD shutdown succeeded, but backup cleanup failed:
snapshot creation ignored `props`. The clone had all ownership tags; the
snapshot definition had none, so the deletion guard correctly retained both.

Local provider fix: after confirmed creation, use `snapshotModify` with
`overrideProps`, read back and verify `snapshot_definition_props` before
cloning. Retry/deletion now verify explicit definition properties rather
than merged/inherited resource properties. Never adopt an existing unowned
snapshot just by name. Regression mocks now reproduce dropped create props.

## Manual recovery and evidence

Verified snapshot UUID `69ab3d60-2261-4f45-b7c0-233d7ef65fc7`, source,
creation time, exact matching clone/session tags and inactive export. Restored
only that snapshot definition's three ownership tags and retried finalization
through UHAPI. Cleanup completed at 08:03:16 UTC; no temporary clone/snapshot
remained. Source VM stayed Running and root volume Ready.

CloudStack's resulting BackedUp status means export-session cleanup completed;
**VBR's job failed**. Manual recovery is not clean acceptance. Both fixes are
local and require updated management/KVM/LINSTOR packages plus a fresh VBR run.

- Private archives under `/tmp/veeam-linstor-attempt1`:
  `linstor-test_2026-10-31_00-55-17.zip` and
  `linstor-validation_2026-10-31_00-57-07.6597551.zip`.
- Sanitized requests: `/tmp/linstor-attempt1-trace.json`.
- Snapshot properties: `/tmp/linstor-backup-snapshot.json`.
- Ownership regression: 18 adaptor tests passed; Checkstyle passed.
- Combined fix reactor compile/test: SUCCESS, 204 tests, zero failures/errors/skips
  (31 management, 32 KVM, 123 Veeam, 18 LINSTOR adaptor). Log:
  `/tmp/linstor-first-backup-fixes-tests.log`.
- Checkstyle SUCCESS across changed core/server/KVM/LINSTOR modules, zero
  violations: `/tmp/linstor-first-backup-fixes-checkstyle.log`.
- `git diff --check` passed. Fixes were not deployed; the zone flag remains enabled.
