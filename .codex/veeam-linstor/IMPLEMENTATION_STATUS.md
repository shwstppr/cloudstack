# Local implementation — 2026-10-31

Later lab update: user-deployed packages were exercised in the first LINSTOR
VBR job. It failed on raw export size, then snapshot-tag cleanup. See
[LINSTOR_FIRST_BACKUP.md](LINSTOR_FIRST_BACKUP.md) for evidence, manual recovery
and local fixes awaiting updated packages. Earlier local-only statements
below describe the original implementation/refactor turns.

The user resumed implementation after the actual VBR NFS full, incremental,
and independent restore baseline. Changes remain local and uncommitted;
no lab packages, services or feature flags were changed in this implementation turn.

## Provider-boundary refactor (2026-10-31)

At the user's request, shared code now uses storage-provider capabilities:

- `PrimaryDataStoreDriver.getBackupStorageCapabilities(StoragePool)` defaults
  to `BackupStorageCapabilities.UNSUPPORTED`. The existing registered driver
  supplies native snapshot support, discovery/full enablement, actual volume
  format, maximum disk count and cleanup timeout for the requested pool/zone.
- `LinstorConfigurationManager` owns and registers all three LINSTOR settings
  below. Their configuration key strings are retained. Core and the Veeam
  control plugin no longer register or read those backend-specific settings.
- Management retains the common backup/transfer/locking/recovery lifecycle.
  Discovery, disk format, restore allocation and validation query capabilities
  through `KVMBackupExportService`; they do not switch on LINSTOR pool type.
- `StorageBackupVolume` carries canonical pool type/UUID, volume UUID and an
  opaque provider volume identifier. The generic DTO does not require a UUID
  storage path. Agent commands use optional `storageBackupVolume` metadata.
- `KVMStoragePool` defaults to unsupported native snapshot export. The
  registered pool must opt in and implement `validateBackupVolume`; LINSTOR
  owns its UUID-path validation, controller calls, snapshots and ownership tags.
- Generic ownership detail keys are now `storage.full.volume` and
  `backup.restore.target`; systemd units use `qemu-nbd-storage-backup-<UUID>`.
  The earlier LINSTOR-specific local payload/detail names were never deployed;
  no live-session migration is needed.
- Shared-code tests use an opted-in RBD pool mock to prove dispatch has no
  LINSTOR dependency. This does not enable the actual RBD storage provider.
  Provider tests verify LINSTOR flags/zone scoping and path validation.

The data path and limitations below still apply. Earlier test counts are
historical; refactor results are recorded at the end of this document.

## Scope and configuration

`kvm.backup.linstor.full.enabled` is a dynamic Zone boolean, default false.
It enables discovery plus experimental single-disk LINSTOR full backup and
raw restore. The earlier discovery-only flag remains discovery-only.
Multi-disk/mixed storage and encrypted volumes are rejected. LINSTOR exports
do not generate QCOW2 checkpoints, dirty bitmaps, or incremental promises.
Snapshots are crash-consistent; application quiescing is not implemented.

`kvm.backup.linstor.session.timeout.seconds` defaults to 14400. Every 60 seconds
management reconciles failed/expired owned sessions. Cleanup remains active
when the feature flag is disabled, so disabling it does not abandon sessions.

## Data path and ownership

- Management persists pool UUID, volume UUID and canonical source path UUID
  as JSON backup detail `storage.full.volume` before agent snapshot requests.
  No credentials or host device paths are persisted in this detail.
- The agent resolves the registered canonical LINSTOR pool and validates the
  source resource group. A native snapshot and separate restored resource
  use deterministic name `cs-vbr-<backup UUID without hyphens>` and matching
  `Aux/cs-vbr-backup`, `Aux/cs-vbr-source`, `Aux/cs-vbr-pool` properties.
  Foreign objects are refused. Cleanup never rolls back/deletes the source.
- Diskless placement resolves the clone locally. A separate UUID-named
  systemd qemu-nbd unit exports the clone with `--read-only --format=raw`.
  Existing image-server NBD/HTTP transport is reused without changes.
- Upload permits only a fresh, unattached Ready raw target allocated by the
  Veeam adapter with detail `backup.restore.target=<volume UUID>`.
  An Up KVM host connects the canonical new target; no backup clone is writable.
  Attachment consumes the marker, as does upload completion/cancellation.
- Upload enters Restoring before export. Successful unregister/NBD shutdown
  precedes Ready; cancellation uses OperationFailed -> RestoreError, preserving
  the existing NFS RestoreFailed -> Ready transition.
- Transfer identity is persisted before agent actions. Unregister drains HTTP
  requests before NBD shutdown, then clone deletion, then source snapshot deletion.
  An unresolved unregister/stop retains ownership for retry. Strict LINSTOR
  unregister does not force-stop an unreachable shared image server.
- Database-backed GlobalLock serializes native operations per VM/download
  or target volume/upload. Duplicate starts reuse Ready sessions; removed
  transfer retries do not touch an already released/reused device.
  Expired/failed cleanup verifies ownership again and retains failed cleanup metadata.

## Validation and remaining work

Targeted Java tests cover management source/restore guards, immutable identities,
checkpoint exclusion, duplicate starts, failed capture cleanup, unregister
failure, removed transfer retries, recovery ownership, raw read-only agent
commands, canonical pool identity, owned clone lifecycle and foreign-object
refusal. Existing NFS start/stop/NBD and Veeam discovery/converter tests run
alongside them. See final validation results appended below.

Run from repository root:

```sh
mvn -o -pl plugins/storage/volume/linstor,plugins/hypervisors/kvm,plugins/integrations/veeam-control-service -am \
  -Dtest=StorageKVMBackupExportServiceTest,KVMBackupExportServiceImplTest,StorageFullBackupCommandWrapperTest,LinstorStorageAdaptorTest,LinstorPrimaryDataStoreDriverImplTest,LibvirtStartBackupCommandWrapperTest,LibvirtStartNBDServerCommandWrapperTest,LibvirtStopBackupCommandWrapperTest,LibvirtFinalizeImageTransferCommandWrapperTest,ServerAdapterTest,VolumeJoinVOToDiskConverterTest,StoreVOToStorageDomainConverterTest,VeeamControlServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Ddownload.plugin.skip=true -Dcheckstyle.skip -Drat.skip=true test
```

Live LINSTOR acceptance is still required: deploy matching management and all
KVM agent artifacts, enable the zone flag, run a VBR full job and independent
new-VM restore, verify guest data/boot and absence of owned resource leaks,
then exercise cancellation/service restart recovery. The VBR oVirt plugin
does not expose supported job/restore automation in this environment; user
UI initiation remains necessary. Use existing health and Veeam trace checks
for evidence collection. Do not substitute a synthetic export for VBR acceptance.
Incremental LINSTOR implementation remains a separate approved-design gate.

### Final local results

- Targeted reactor build/test: SUCCESS, 189 tests (28 management, 24 KVM,
  122 Veeam, 15 LINSTOR adaptor), zero failures/errors/skips.
  Log: `/tmp/linstor-veeam-final-tests.log`.
- Additional cleanup regression run: SUCCESS, 12 tests (7 LINSTOR agent,
  5 image-server finalization), including six tests beyond the preceding run.
  Total distinct Java tests: 195. Log: `/tmp/linstor-cleanup-tests.log`.
- Checkstyle: zero violations across API, core, server, KVM, Veeam, LINSTOR;
  KVM checked again after final tests. Logs: `/tmp/linstor-final-checkstyle.log`,
  `/tmp/linstor-cleanup-checkstyle.log`.
- Image-server registry unit tests: 6 passed;
  `/tmp/linstor-imageserver-unit-tests.log`.
- HTTP/NBD transport suite could not start the server: Python `nbd` (libnbd)
  is absent locally. Stopped the run after confirming the dependency error;
  no transport pass claimed. `/tmp/linstor-imageserver-tests.log` and
  `/tmp/imageserver_test_f4l_69xf/imageserver.log`.
- `git diff --check` passed. Maven's sandboxed Develocity cache produced
  read-only-cache warnings; Maven build/test/checkstyle goals succeeded.

The suite covers default NFS command paths; actual NFS backup/restore remains
the previously validated baseline. No new NFS lab job was run this turn.

### Provider-refactor validation

- Reactor compile/test SUCCESS: 216 tests, zero failures/errors/skips
  (31 management, 31 KVM, 123 Veeam, 31 LINSTOR driver/adaptor).
  Log: `/tmp/storage-backup-provider-tests.log`.
- Checkstyle SUCCESS across API, core, engine API, server, KVM, Veeam and
  LINSTOR: zero violations. Log: `/tmp/storage-backup-provider-checkstyle.log`.
- New coverage includes registered-driver delegation/default unsupported,
  disabled-provider rejection, generic opaque storage handles, agent opt-in,
  provider-owned UUID validation, zone-scoped flags/timeouts, and per-pool
  discovery filtering with pagination while retaining NFS connection metadata.
- Source audit: no LINSTOR references remain in the shared backup API,
  backup command DTOs, management backup service or Veeam production source.
  Pre-existing LINSTOR integration outside this implementation was preserved.
- `git diff --check` passed. No deployment, lab changes or new NFS job.
