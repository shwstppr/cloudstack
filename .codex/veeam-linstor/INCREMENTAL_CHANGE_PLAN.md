# Proposed LINSTOR Veeam incremental implementation

Status: implementation in progress after full backup and independent restore
passed on 2026-10-31. Local uncommitted changes now include the provider
capability/flag, LINSTOR snapshot checkpoint lifecycle, QCOW2 COW export, and
allocation-depth dirty extents. Java build/test validation and live VBR
acceptance remain pending. The feature is default-off. See
[FEASIBILITY.md](FEASIBILITY.md) for protocol and storage evidence. Existing
NFS behavior must remain unchanged.

## Goal and initial strategy

Make VBR produce genuine incremental restore points for a single-disk,
LINSTOR-backed VM. The Veeam session must report the prior and new checkpoint,
request dirty extents, transfer fewer bytes for a controlled small change,
and restore the full and incremental points correctly.

Start with **content-diff export** using a retained LINSTOR snapshot baseline
and QCOW2 overlay. The current Veeam full export is raw and deliberately
advertises no checkpoint, so the incremental-capable path must export a
QCOW2/COW view from its first full backup. On later runs, make a new
point-in-time LINSTOR snapshot, compare it to the last successfully backed-up
baseline, expose the resulting QCOW2 delta, and provide that delta's allocated
extents through the existing image-transfer `dirty` context. This follows the
content-diff mechanism already used by LINSTOR's NAS backup path, but needs a
separate VBR implementation and live protocol proof.

This first strategy reduces data sent to VBR but still compares disk contents
to find changed clusters and can read the full disk on every incremental. It
does not claim storage-layer CBT. A QEMU bitmap path can be considered later
to reduce comparison reads; transient bitmaps on raw storage must force a full
backup after their QEMU process/lifecycle is lost.

## Proposed file-by-file changes

| File or area | Proposed change | Required evidence |
| --- | --- | --- |
| `api/src/main/java/org/apache/cloudstack/backup/BackupStorageCapabilities.java` | Add a generic incremental-export capability/mode, default unsupported. Keep storage-provider names out of shared code. | Provider unit tests; unsupported drivers retain current behavior. |
| `plugins/storage/volume/linstor/src/main/java/org/apache/cloudstack/storage/datastore/util/LinstorConfigurationManager.java` | Add a zone-scoped, default-off incremental setting; incremental requires the full-export capability. | Config registration/scoping tests; flag-off remains full-only. |
| `plugins/storage/volume/linstor/src/main/java/org/apache/cloudstack/storage/datastore/driver/LinstorPrimaryDataStoreDriverImpl.java` | Advertise the incremental capability only for eligible pools; own baseline snapshot and staged snapshot lifecycle through the provider. | Provider tests for disabled mode, eligibility, and failed/finalized lifecycle. |
| `core/src/main/java/org/apache/cloudstack/backup/StorageBackupVolume.java`, `core/src/main/java/org/apache/cloudstack/backup/StartBackupCommand.java`, and related backup command DTOs | Carry generic checkpoint IDs and opaque provider export identity; do not add LINSTOR names, paths, or snapshot APIs to core. | DTO serialization and historical-session cleanup compatibility. |
| `server/src/main/java/org/apache/cloudstack/backup/KVMBackupExportServiceImpl.java` | Persist the last successful checkpoint/baseline before starting work; stage a new checkpoint; advance the baseline only after VBR image-transfer and backup finalization succeed. On failure, retain the prior baseline and clean only the staged objects. Force a full when the baseline is missing, stale, resized, or invalid. | Management tests for duplicate calls, failed upload/finalize, restart recovery, resize, and lost baseline. |
| `plugins/storage/volume/linstor/src/main/java/com/cloud/hypervisor/kvm/storage/LinstorStorageAdaptor.java` and `LinstorStoragePool.java` | Retain an owned LINSTOR baseline snapshot; stage a second point-in-time snapshot for the current run; make both read-only views available for content comparison; clean/rotate only after successful finalize. | Agent/provider tests for ownership, restart, duplicate requests, abort, and no source mutation. |
| `plugins/storage/volume/linstor/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LinstorBackupSnapshotCommandWrapper.java` or a dedicated LINSTOR Veeam export wrapper | Reuse the existing safe `qemu-img` content-diff logic to create full and delta COW views from the two snapshots. Keep the parent available for the lifetime of the transfer. | QEMU image tests: full view, delta contents, sparse extents, equal disks, resize rejection, and missing-parent fallback. |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtStartNBDServerCommandWrapper.java` and image-server registry/backend (`scripts/vm/hypervisor/kvm/imageserver/handler.py`, `backends/nbd.py`) | Export the logical QCOW2 view read-only. For incremental requests, return only changed/allocated delta extents; for full requests return complete logical allocation extents. Keep URI/auth and cleanup behavior unchanged. | Image-server tests and actual VBR trace showing `dirty=True` produces only changed ranges. |
| `plugins/integrations/veeam-control-service/src/main/java/org/apache/cloudstack/veeam/adapter/ServerAdapter.java` | Report provider-backed COW format only when the export is truly COW; expose the active/from and new/to checkpoint IDs; permit backup only when provider capability and checkpoint state are valid. | Veeam API tests for full, incremental, stale baseline, and unchanged NFS responses. |
| `plugins/integrations/veeam-control-service/src/main/java/org/apache/cloudstack/veeam/api/converter/VolumeJoinVOToDiskConverter.java`, `BackupVOToBackupConverter.java`, `UserVmVOToCheckpointConverter.java` | Map the provider's actual export format and persistent checkpoint lineage into the oVirt-compatible payload. | Converter tests; no false `COW` metadata for a raw-only full export. |
| Tests in `api`, `server`, `plugins/storage/volume/linstor`, `plugins/hypervisors/kvm`, `plugins/integrations/veeam-control-service`, and `scripts/vm/hypervisor/kvm/imageserver/tests` | Add lifecycle, data-diff, extents, fallback, and regression tests. | Targeted Java and Python suites; no NFS regressions. |
| `.codex/veeam-linstor/VBR_VALIDATION.md` and `IMPLEMENTATION_STATUS.md` | Record actual full/incremental sessions, byte counts, restore-point type, restored content and boot evidence. | Live VBR logs and user-confirmed boot check. |

This list is provisional where existing tables may already hold the needed
checkpoint metadata. Before implementation, verify whether `BackupVO`,
`BackupDetails`, and the current checkpoint detail keys can safely retain the
provider baseline across completed sessions and VBR retention; add a schema
change only if they cannot.

Implementation note: the local code now carries provider checkpoint IDs from
management to the LINSTOR snapshot exporter. Every incremental-capable full
backup exports a QCOW2 COW view; later exports are safely rebased onto the
previous successful checkpoint. QEMU allocation-depth metadata is requested
only for those delta exports. This is still unvalidated end to end with VBR,
so it must not be enabled in the lab until package tests/build pass and the
live acceptance gates below are run.

## Ordered implementation and validation gates

1. Confirm on the deployed KVM/QEMU build that a read-only QCOW2 view backed
   by the current LINSTOR snapshot reports the intended `base:allocation`
   ranges. Confirm a safe-rebased delta reads as the new point and allocates
   only changed clusters. This is local disposable-image validation; do not
   mutate the live VM.
2. Implement provider capability and the staged/committed baseline lifecycle,
   default off. Full backup must still work and must establish a real baseline
   checkpoint before incrementals are advertised.
3. Implement QCOW2 view export and the image-server dirty-extent response.
   Keep source snapshots until transfer finalization; advance/delete the
   baseline only after success. Missing/lost state falls back to full.
4. Exercise a live VBR full backup, mutate a known guest dataset, run two
   incrementals with further changes/deletions, and inspect logs for
   `can be backed up incrementally: True`, reused/new checkpoints,
   `dirty: True`, incremental restore-point type, and materially lower
   transferred bytes than full.
5. Restore the full point and both incremental points independently to new
   VMs. Compare expected dataset hashes and boot each restored VM. Inject a
   failed transfer and a missing baseline; confirm cleanup and full fallback.

Do not label an internal QCOW2 delta as Veeam incremental until gates 4 and 5
pass. The existing NFS incremental baseline is not evidence for LINSTOR.
