# Proposed file-by-file LINSTOR Veeam changes

The user approved the discovery/format and full backup/restore slices on
2026-10-30. Those slices were implemented and passed the recorded targeted
tests. The user installed the resulting packages; on 2026-10-31 a real VBR
full backup and independent new-VM restore completed, and the user confirmed
the restored VM booted. See `IMPLEMENTATION_STATUS.md` and `VBR_VALIDATION.md`.
Findings and evidence are in `FEASIBILITY.md`.

The user approved proceeding with LINSTOR incrementals on 2026-10-31 after the
full path passed. The separate file-by-file proposal is
`INCREMENTAL_CHANGE_PLAN.md`; incremental implementation is now underway.

Implementation note: the initial flag was introduced in `VeeamControlService` as
`integration.veeam.control.linstor.discovery.enabled` (Zone scope, default
false). It enables discovery only, leaving `validateInstanceStorage()`'s
backup guard intact. The separate full-export capability is now
`kvm.backup.linstor.full.enabled` (Zone scope, default false); it enables
LINSTOR discovery and the single-disk transport together.
Raw-format correction is based on pool identity in every disk/attachment
conversion path; LINSTOR discovery does not fabricate NFS connection metadata.

Provider-boundary refactor requested 2026-10-31: all three LINSTOR settings now
live in `LinstorConfigurationManager`. Shared code obtains a generic
`BackupStorageCapabilities` from `PrimaryDataStoreDriver`, and agent commands
carry generic `StorageBackupVolume` identities. See IMPLEMENTATION_STATUS.md.

## First slice: truthful discovery, behind a feature flag

| File | Proposed change | Validation |
| --- | --- | --- |
| `api/src/main/java/org/apache/cloudstack/backup/KVMBackupExportService.java` | Add a zone-scoped experimental LINSTOR export capability flag, default false, shared by discovery and backup validation | Flag-off leaves existing NFS behavior unchanged |
| `plugins/integrations/veeam-control-service/src/main/java/org/apache/cloudstack/veeam/adapter/ServerAdapter.java` | Replace the fixed file-storage assumption with explicit capabilities; include LINSTOR storage domains only when enabled; ensure backup validation checks the same capability. Preserve VM ownership/zone filters; live VM listing already includes LINSTOR | Enabled/disabled LINSTOR discovery; NFS/local/shared regression tests; mixed-storage rejection until supported |
| `.../api/converter/VolumeJoinVOToDiskConverter.java` | Accept actual backend format information resolved by ServerAdapter from canonical pool identity, rather than guessing from path/name or template format. Report raw DRBD accurately and omit inapplicable QCOW2 metadata | Assert raw LINSTOR, existing QCOW2 NFS, disk list and attachment metadata agree |
| `.../api/converter/StoreVOToStorageDomainConverter.java` | Review domain metadata/capabilities for LINSTOR, including unsupported actions; change only fields required by the observed VBR contract | Exact discovery payload assertions |
| `.../api/DataCentersRouteHandler.java`, potential new `StorageDomainsRouteHandler.java` | Only if VBR follows top-level storage-domain links: implement exact GET/list/lookup semantics with existing authorization checks. Current live top-level routes return 404 | Contract tests based on actual trace; avoid speculative endpoint expansion |
| `.../src/test/java/org/apache/cloudstack/veeam/adapter/ServerAdapterTest.java` | Cover discovery and backup storage validation separately, including the current fail-before-start case | No tests asserting that allowlisting alone makes backup transport work |
| `.../src/test/java/org/apache/cloudstack/veeam/api/converter/VolumeJoinVOToDiskConverterTest.java` | Cover actual raw/cow reporting and storage-domain UUID consistency | NFS regression plus LINSTOR raw case |

Do not expose an enabled backup capability to VBR until its full transport
is implemented. The flag remains off by default throughout development.

## Second slice: single-disk full backup and independent restore

Preferred design to validate: native LINSTOR snapshot -> separate temporary
restore resource -> diskless read-only raw export on the selected KVM host
-> existing image-server NBD transport. Original running guest/source volume
is never a writable export or restore target. Start crash-consistent; do not
advertise application consistency without guest quiescing evidence.

The existing NAS pull-mode scratch implementation is an alternative source
of reusable mechanics, not a reason to route VBR through the NAS provider.
The implemented session shape uses the existing backup/image-transfer APIs
with typed canonical identities in agent commands and ownership in backup
details. See `VBR_VALIDATION.md` for the observed NFS protocol baseline.

| File | Proposed change / invariant |
| --- | --- |
| `server/src/main/java/org/apache/cloudstack/backup/KVMBackupExportServiceImpl.java` | Branch by canonical pool type/capability. Keep file storage path unchanged. Persist LINSTOR session IDs/ownership; full-only LINSTOR does not create false QCOW2 checkpoint baselines. Coordinate download, restore upload, finalize, cancel and bounded recovery cleanup |
| `core/src/main/java/org/apache/cloudstack/backup/StartBackupCommand.java` and `StartBackupAnswer.java` | Add typed per-volume backend/session metadata with backwards-compatible constructors/serialization, or a dedicated LINSTOR command if separation is cleaner. Include source volume/pool UUID and read-only export handles; no unvalidated arbitrary device paths |
| `core/src/main/java/org/apache/cloudstack/backup/FinalizeImageTransferCommand.java`, `StopBackupCommand.java`, `StopBackupAnswer.java` | Existing transfer finalization and backup-stop boundary. Carry sufficient session identity for idempotent release; do not invent a public API. Resource cleanup must follow outstanding transfer ownership |
| `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtStartBackupCommandWrapper.java` | Add/dispatch LINSTOR full route without invoking file-backed checkpoint or bitmap commands on raw storage. Preserve NFS checkpoint path |
| `.../wrapper/LibvirtCreateImageTransferCommandWrapper.java` | Reuse NBD registration and optional image-server TLS for the prepared export; verify read-only capability for backup transfers |
| `.../wrapper/LibvirtFinalizeImageTransferCommandWrapper.java`, `LibvirtStopBackupCommandWrapper.java` | Stop only owned NBD exports; release temporary diskless attachment, restored resource and snapshot in dependency order; make retries safe |
| `.../wrapper/LibvirtStartNBDServerCommandWrapper.java` / `LibvirtStopNBDServerCommandWrapper.java` | Review/reuse support for raw block exports. Ensure backup is read-only; restore is writable only to a newly allocated target; retain bounded process/session cleanup |
| `plugins/storage/volume/linstor/src/main/java/com/cloud/hypervisor/kvm/storage/LinstorStorageAdaptor.java` | Add owned snapshot/temporary-restore-export helpers using actual DevelopersApi operations. Reuse diskless placement/device-wait and ownership-tag patterns; do not manipulate active backing LVs directly |
| `.../LinstorStoragePool.java` | Expose narrow snapshot/export lifecycle methods to wrappers; preserve canonical source identity |
| `engine/schema/src/main/java/org/apache/cloudstack/backup/BackupDetailVO.java`, `dao/BackupDetailsDao.java`, `dao/BackupDetailsDaoImpl.java`; conditionally `BackupVO.java`/`ImageTransferVO.java` | Prefer existing backup details over schema changes. Persist enough identity for cancellation/restart recovery; keep credentials out of metadata/logs. Add a migration only if existing details cannot safely represent the lifecycle |
| `scripts/vm/hypervisor/kvm/imageserver/handler.py`, `backends/nbd.py` | Reuse existing GET/range/OPTIONS/extent and write paths. Change only contract gaps demonstrated by trace/tests; no synthetic dirty-extent promise without a verified source |

Some command/schema edits above are conditional: final names and persistence
choice must follow the real existing boundary, not become a broad refactor.
In particular, there is no assumed LINSTOR CBT API.

Restore must allocate a **new** LINSTOR volume via CloudStack, attach/export
it on a suitable host, accept the actual VBR transfer representation, flush,
finalize state and boot a new VM. If VBR insists on QCOW2 upload, define and
test a real conversion stage; never write a QCOW2 stream directly into a raw
guest disk and label it restored.

## Tests and evidence for the second slice

- Extend `server/src/test/java/org/apache/cloudstack/backup/KVMBackupExportServiceImplTest.java`:
  LINSTOR feature gate, full-only checkpoint behavior, canonical identity,
  new restore target, finalization/cancel/retry and unsupported mixed pools.
- Extend KVM start/create/finalize image-transfer/NBD wrapper tests for raw
  read-only snapshot exports, failed attach, canceled transfers and cleanup.
- Extend `plugins/storage/volume/linstor/src/test/java/com/cloud/hypervisor/kvm/storage/LinstorStorageAdaptorTest.java`:
  owned snapshot/clone lifecycle, placement on diskless hosts, missing devices,
  API failure cleanup and restart reconciliation. Never delete unowned objects.
- Run relevant existing image-server lifecycle/range/extent tests and NFS
  backup/export regressions. Add new protocol cases from the redacted VBR trace.
- Add `.codex/veeam-linstor/checks/backup_full.py` after the workflow exists:
  concise result, separate evidence, own disposable objects only, no tokens
  in reports. Verify known guest data, backup, independent restore, hashes,
  boot, and absence of temporary-resource leaks.
- Lab acceptance: successful VBR full job and VBR-owned restore to a new VM;
  restored content matches; cancellation and service restart leave source
  intact; NFS full-backup workflow still succeeds.

## Incrementals: separate gate, not included in the above approval

Record VBR version/build and actual full/incremental wire behavior first.
Evaluate verified changed-extent sources and baseline persistence/reset
semantics. NAS content-diff can inform experiments but does not establish
VBR acceptance. Require two VBR incremental restore points plus restores to
the full and incremental points, measured source/transferred bytes, and a
tested fallback to full on lost/reset tracking before making claims.

## Baseline obtained; remaining acceptance

Actual VBR full backup, incremental backup and restore were observed on NFS;
the user confirmed the restored VM booted. Do not repeat that baseline.
LINSTOR acceptance still requires deploying matching management/KVM artifacts,
enabling the full flag in the lab zone, and a UI-triggered VBR full backup
and independent restore with content verification and cleanup checks.
