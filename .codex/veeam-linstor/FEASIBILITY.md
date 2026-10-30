# Phase 0: Veeam / LINSTOR feasibility

Assessment date: 2026-10-30. Repository branch `poc-veeam-kvm-linstor`,
HEAD `9cf255f8c47b24eb088223d1a37988e2e87df22d`, root Maven version
`24.0.0-SNAPSHOT`. Existing user changes preserved. This phase changed
documentation only; no integration source or live backup behavior changed.

## Decision

The LINSTOR appliance, diskless KVM clients, and CloudStack deployment are
operational. The existing Veeam path is **not yet LINSTOR compatible**.
Discovery, format reporting, backup initiation, export setup and restore
target handling all need consideration. Simply adding `Linstor` to an
allowlist would bypass one validation while leaving later failures.

Full backup transport is technically plausible using a point-in-time
LINSTOR snapshot restored into a separate temporary resource, attached
diskless and exported read-only through the existing NBD/HTTP transport.
This is a proposed implementation, not a successful VBR backup test.

Genuine Veeam incremental feasibility remains **undetermined**. Need an
actual VBR session trace and a demonstrated incremental restore chain.
Neither LINSTOR snapshot success nor NAS-provider incrementals establishes
Veeam incremental interoperability.

## Environment and HTTPS observations

The reusable health check passed after the user configured SSL:

```sh
python3 .codex/veeam-linstor/checks/deployment.py
```

Report: `/tmp/cloudstack-lab-checks/20261030T141143Z-cbca2c/report.json`.
Prior fresh deployment, cross-host restart and cleanup checks also passed;
see `checks/README.md` and `VM_DEPLOYMENT_VALIDATION.md`.

Endpoint:
`https://qa6-beyond-nfs-e29e5aeb-kvm-mgmt1.lab:8090/ovirt-engine/api`.
Verified authenticated HTTP 200 with the corrected username `acsadmin`.
No passwords, Authorization headers or tokens are stored in this document.

Certificate subject matches the `.lab` hostname; issuer `MBX Lab CA`.
SAN includes the full hostname, short hostname, localhost and 172.120.0.107.
SHA256 fingerprint:
`DF:A4:E4:39:60:C6:52:0C:80:A9:54:F2:4C:B4:61:DF:B0:23:59:42:B2:B1:51:9C:0C:9C:93:5C:34:DE:AC:C3`.
Used curl's `--resolve` and explicit certificate verification for the probes;
did not modify DNS, credentials, SSL settings or global trust configuration.
Captured public certificate and sanitized discovery summary under
`/root/linstor-provisioning/` on management.

## Live discovery evidence

Paths below are relative to `/ovirt-engine/api`; requests were read-only.

| Request | Result |
| --- | --- |
| `GET /` | 200; API root |
| `GET /vms?max=10` | 200; includes `linstor-validation` (`3b89203c-2667-4c76-9e10-4d85583e30d3`), status up |
| `GET /disks?max=10` | 200; includes ROOT-3 (`4e484f68-8fe9-4c45-9070-1d9f8cbefe64`), status ok |
| `GET /vms/<vm>/diskattachments` | 200; ROOT-3 attachment and storage-domain reference to LINSTOR pool |
| `GET /datacenters/<DC>/storagedomains` | 200; only Primary1/Primary2 NFS pools; LINSTOR omitted |
| `GET /storagedomains` and `/storagedomains/<LINSTOR pool>` | 404; do not assume these routes exist merely because disk metadata contains a reference |

The live VM endpoint already returns a LINSTOR-backed VM. The confirmed
file-storage restriction is at **backup initiation** and datacenter storage
domain listing, not an explicit NFS-only filter in `listAllInstances()`.
That method filters by compatible zone, KVM hypervisor, VM type and ownership;
its DAO query has no storage-type predicate. VBR behavior when a disk's
referenced storage domain is undiscoverable remains to be observed.

The plugin reports ROOT-3 as `format=cow` with `qcow_version=qcow2_v3`.
Libvirt's actual running domain reports `device=disk`, `type=block`,
`driver type=raw`, source
`/dev/drbd/by-res/cs-4e484f68-8fe9-4c45-9070-1d9f8cbefe64/0`.
Format metadata must follow the actual backend rather than assume the
template's QCOW2 format describes the allocated LINSTOR volume.

## Current source paths and blockers

Paths are repository relative.

| Area | Evidence / implication |
| --- | --- |
| `plugins/integrations/veeam-control-service/.../VeeamControlServer.java` | Embedded Jetty, `/ovirt-engine` context; uses configured management keystore for HTTPS |
| `.../filter/BearerOrBasicAuthFilter.java`, `.../sso/SsoService.java` | Basic fallback and bearer authentication; password-grant SSO endpoint exists; actual VBR auth exchange not captured |
| `.../adapter/ServerAdapter.java` | `SUPPORTED_STORAGE_TYPES` contains Filesystem, NetworkFilesystem, SharedMountPoint only. Used in datacenter storage discovery and `validateInstanceStorage()`, which `createInstanceBackup()` calls before starting backup |
| `.../api/converter/VolumeJoinVOToDiskConverter.java` | Converts `VolumeJoinVO.getFormat()` to raw/cow and always sets qcow_version; observed incorrect cow metadata for raw LINSTOR root |
| `.../api/ImageTransfersRouteHandler.java` | Transfer create/list/get/cancel/finalize routes, delegates to ServerAdapter and KVM export service |
| `server/.../backup/KVMBackupExportServiceImpl.java` | `startBackup()` resolves every volume with `getVolumePathForFileBasedBackend()`. Prefix switch accepts NFS/shared paths, not LINSTOR. Upload and stopped-VM download also resolve file paths |
| `core/.../backup/StartBackupCommand.java` | Carries file path/UUID map and checkpoint IDs, not typed LINSTOR snapshot-session metadata |
| `plugins/hypervisors/kvm/.../wrapper/LibvirtStartBackupCommandWrapper.java` | Running backup always creates checkpoint XML and file-based scratch; stopped backup invokes `qemu-img bitmap --add`. Cannot apply that QCOW2 persistent-checkpoint assumption to raw DRBD |
| `.../wrapper/LibvirtCreateImageTransferCommandWrapper.java` | Registers file or NBD backend over Unix control socket and returns host:54322 `/images/<transfer>` URL. Optional TLS supported; actual VBR proxy reachability and image-server TLS remain unverified |
| `scripts/vm/hypervisor/kvm/imageserver/handler.py` | GET with byte ranges, OPTIONS, extents endpoint, PUT/PATCH and flush operations exist. This is not upload-only code |
| `.../imageserver/backends/nbd.py` | libnbd reads/writes, allocation extents and QEMU dirty-bitmap contexts. Presence of those handlers is not evidence VBR accepts custom changed extents |
| `plugins/storage/volume/linstor/.../LinstorVMSnapshotStrategy.java` | Native LINSTOR snapshot and multi-snapshot calls already exist; no need to invent a LINSTOR snapshot API |
| `.../LinstorStorageAdaptor.java`, `LinstorStoragePool.java` | Canonical pool/resource identity, diskless access and temporary backup scratch lifecycle already implemented for NAS pull-mode backup |

The current backup service creates `toCheckpointId` and derives
`fromCheckpointId` from VM details. Finalization updates active/last
checkpoint state. A LINSTOR full-only route must not invent a persistent
checkpoint or advertise an incremental baseline just to satisfy that model.
Exact full-backup checkpoint fields expected by VBR are still a protocol gate.

## PR #14194 and reusable NAS code

Verified via GitHub metadata and local ancestry:

- [PR #14194](https://github.com/apache/cloudstack/pull/14194),
  "Linstor add support for incremental nasbackup", merged to `4.22`
  on 2026-10-23.
- Head `eaf97bd3e301d6675247cbbdae9786f92fd4b6a9`.
- Merge `0d380a015a588da5e0e09700b1711bec4e672a21` is an ancestor of HEAD.
- Adds NAS-provider LINSTOR **content-based** pull backup, chain metadata,
  scratch placement/cleanup, and restore handling. Raw LINSTOR storage does
  not use the file-backed persistent dirty-bitmap path.
- `nas.backup.incremental.enabled` gates that NAS functionality. It is not
  a VBR protocol capability flag and must not be repurposed as one.

Reuse verified storage identity, diskless attach, scratch ownership and
cleanup patterns where appropriate. Do not change the existing NAS or NFS
behavior to make the Veeam route work. Current source uses distinct services:
`KVMBackupExportServiceImpl` for external orchestration, `NASBackupProvider`
for NAS-owned chains, and `plugins/backup/veeam/.../VeeamBackupProvider.java`
for the existing provider integration. They are not interchangeable paths.

## Proven and outstanding storage/data-plane facts

Proven: controller/satellite 1.35.3, Ubuntu DRBD 9.3.4, EL8 diskless hosts
DRBD 9.2.20, LVM-thin snapshot provisioning/restore, read-only access to a
separate restored resource, cross-host checksums, source writes independent
of snapshot contents, cleanup, and a guest booting on both hosts.

Host1 observed libvirt 8.0.0, QEMU/qemu-img 6.2.0, Python 3.6.8 with libnbd
available, and the image-server package installed. Source/deployed parity
for every Veeam class has not been established merely by those versions.

Still unproven: actual image-server transfer against a LINSTOR snapshot,
raw/cow negotiation with VBR, data-plane TLS/reachability from the proxy,
restore into a new LINSTOR volume through VBR, guest application consistency,
multi-disk boundaries, crash recovery and complete leak-free failure cleanup.

No VBR server/proxy addresses, version/build, existing successful NFS job,
or VBR request logs have been supplied. No VBR-generated traffic was captured
or manufactured during the curl discovery probes.

## Required gates

1. Capture a redacted successful NFS VBR full-backup/restore exchange from
   the configured VBR/proxy: auth, discovery, backup/checkpoint payloads,
   image-transfer direction/format, OPTIONS/GET/ranges/extents, finalization.
2. Approve the file-by-file proposal in `CHANGE_PLAN.md` before integration
   source changes, as required by `.codex/AGENTS.md`.
3. Implement and test a feature-gated single-disk LINSTOR full backup and
   restore through the observed protocol. Validate independent restored
   content and guest boot, then exercise cancellation/restart/cleanup.
4. Assess incremental tracking and protocol compatibility separately. Only
   claim genuine Veeam incrementals after VBR creates actual incremental
   restore points and a tested full-plus-incremental chain restores correctly.
