# VBR validation and current implementation status

First LINSTOR attempt (2026-10-31): failed; see
[LINSTOR_FIRST_BACKUP.md](LINSTOR_FIRST_BACKUP.md) for both diagnosed bugs,
manual cleanup and fixes. It does not establish successful LINSTOR backup.

The user approved the implementation plan on 2026-10-30 and noted that
oVirt hypervisor jobs may not be controllable through VBR REST/PowerShell.
Do not assume that generic VBR automation support covers this plugin.
VBR is accessible over SSH at Administrator@172.120.0.131. The installed
VBR build is 13.1.0.308 and oVirt KVM plugin build is 13.8.0.281. Credentials
are provided in the conversation; do not put them in this document.

Use console-triggered operations if that is the supported route for the
installed version. The agent can prepare the environment and collect
CloudStack/KVM-side evidence while the user starts the VBR job/restore.
Neither driving CloudStack's APIs directly nor testing HTTP transfers alone
counts as a real VBR job or restore point.

Veeam's [API integration guide](https://www.veeam.com/kb4311) distinguishes
REST and PowerShell capabilities at product level; it is not a verified
oVirt-specific job/restore automation contract for the user's installed build.
The [oVirt backup workflow](https://helpcenter.veeam.com/docs/vbr/userguide/ovirt_backup_job_how.html)
describes manager API interactions. Verify actual traffic for this lab.

## Local changes completed

- Added zone-scoped `integration.veeam.control.linstor.discovery.enabled`,
  default false, to the control plugin and registered its configuration key.
- Storage-domain discovery includes LINSTOR only when that flag is enabled.
- Disk list, disk lookup, create/attach replies and VM disk attachments use
  canonical pool identity to report actual RAW format for LINSTOR despite
  template/volume metadata containing QCOW2. Raw disks omit qcow_version.
- Experimental LINSTOR domains do not fabricate an NFS mount/address from
  the controller URL. Their final oVirt storage representation still needs
  validation against actual VBR behavior.
- Backup initiation still rejects LINSTOR. This deliberate guard prevents
  discovery from advertising a working export before transport exists.
- Existing file-storage backup/export code was not changed. No new top-level
  storage-domain routes were invented without a VBR trace requiring them.

These are local source changes, **not deployed** to management or KVM.
No full LINSTOR export/restore or incremental implementation is claimed.

Validation:

```sh
mvn -o -pl plugins/integrations/veeam-control-service \
  -Dcheckstyle.skip -Drat.skip=true test
```

Result: 335 tests, zero failures/errors/skips. Also ran Maven validate and
`git diff --check`. Build tooling emitted read-only Develocity-cache warnings
in the sandbox, but the build/test goals completed successfully.

Added `checks/veeam_trace.py` and three sanitizer tests. A read-only live
collection found 88 request-metadata records since 14:00 UTC, including
auth/discovery probes, with no backup/image-transfer sequence. This is not
attributed to VBR without session correlation. Raw headers/query values are
not saved. It does not replace the requested backup/restore trace.

## Next protocol gate

1. Record VBR build, oVirt plugin build, manager entry and worker/proxy IPs.
   Ensure the manager hostname resolves and MBX Lab CA is trusted there.
   Verify workers can reach management HTTPS and the intended KVM data ports;
   do not assume guest-to-host reachability through a virtual router.
2. Choose a disposable NFS-backed VM for an existing supported baseline
   job. Record its canonical IDs, VBR job/session ID and operation start UTC.
   Do not use the current LINSTOR validation VM as the NFS baseline.
3. Trigger a full backup in the supported VBR UI (or a documented, verified
   API for that installed build). Collect the sanitized control request
   summary, job logs and selected redacted payloads for backup/checkpoint
   creation, image-transfer direction/format, OPTIONS/range/extents and finalize.
   Keep auth tokens, cookies, private keys and guest credentials out of reports.
4. Trigger a restore to a new VM/volume, capture the write-side protocol and
   correlate VBR completion with restored content and guest boot.
5. Use those observed contracts to finish the LINSTOR full-export/restore
   slice in CHANGE_PLAN.md. Retain the existing NFS workflow. Then validate
   the actual VBR LINSTOR backup and independent restore.

The user has already approved source implementation; do not ask for the
same approval again. The remaining dependency is VBR access/session evidence,
not another permission to edit code. Incrementals retain their separate
tracking/protocol/restore-chain feasibility gate.

## NFS baseline prepared, 2026-10-30 18:49 UTC

User reports the worker is now healthy and authorizes trying NFS VM operations.
Verified PowerShell 7.6.0 is already installed on Windows (`pwsh.exe`); use it
for the Veeam module, which cannot load in the SSH default Windows PowerShell
5.1. The module loaded in 7.6 and command enumeration found no oVirt/KVM job
creation/start/restore controls. `Get-VBRJob` returned no jobs. The similarly
named `Get-VBRPluginJob` covers standalone application plug-ins, not oVirt
hypervisor jobs. The documented VBR REST job types also omit oVirt; do not use
undocumented internal .NET/plugin endpoints as a substitute.

Prepared a disposable NFS guest for a VBR console-triggered baseline:

- Name: `nfs-veeam-baseline-20261030184905`
- VM UUID: `f6992b0f-4e3e-4cbc-8dfb-c12c8a449a06`, DB ID 14,
  instance `i-2-14-VM`, admin account.
- Root UUID/path: `4f87ada1-c3f2-43fa-802c-c6c758a89c28`, 8 GiB,
  Ready on Primary1 (`76160834-a639-39a8-bf3d-b56fa7eebabc`), NFS.
- Running on host2; Small Instance offering; existing CentOS 5.5 built-in
  template, existing `linstor-validation-net`, guest `10.1.1.88`.
- Console reached login; router recorded DHCP ACK, guest ping succeeded,
  SSH banner `SSH-2.0-OpenSSH_4.3` returned. Initial readiness probe preceded
  DHCP completion; retry after DHCP succeeded.
- No changes to the retained LINSTOR validation VM or its disk.
- Deployment record on management server:
  `/root/linstor-provisioning/nfs-veeam-baseline.json`.
- Pre-job sanitized trace: `/tmp/veeam-nfs-baseline-prejob-trace.json`, two
  discovery requests only; no VBR backup/restore evidence.

Asked the user to create/run a VBR job for this guest using their working
worker and repository, and provide the job name. No VBR backup, restore point
or restore has yet been created or validated by this step. Keep the guest
for that test; do not mistake the stopped Veeam worker `bye` for the baseline.

User started job `nfs-test`, policy UUID
`bc0f7abf-72fe-4b8b-b474-19e66e7c8fa0`, at 18:52:41 UTC. VBR session UUID
`30ef30cf-7345-48ca-8280-bbc9157d1bf3`; VM task UUID
`02f11c37-2281-49da-a6d6-785bf64456a2`. Job logs confirm the target baseline
VM UUID above and no prior successful full session. The policy says
IncrementalBackup; this alone proves neither an incremental restore point nor
an incremental data transfer.

Worker `bye` (VM DB ID12, UUID `f14587fa-c62b-4e28-89ab-ef2bc25a305d`)
started on host1 at `172.120.0.248`. Worker preparation checks updates, then
requests an instance snapshot before update installation. At 18:53:31 UTC that
snapshot (`452f3f6b-8c2f-45fe-b6b2-59550c900976`, snapshot DB ID3) hit the
previously diagnosed null volume-list bug. VBR polls the failed snapshot as
Locked because the adapter maps all non-Ready states to Locked. The worker
is running and its QEMU agent responds; TCP443 and TCP19000 are reachable.
Observe the job through its snapshot timeout before concluding backup failed;
the earlier worker test continued after the warning.

The constructor fix and regression test pass 33 snapshot/revert tests; a
`cloud-core-24.0.0-SNAPSHOT.jar` was packaged locally from those compiled
classes while this job waited. It has not been deployed. No service restart
was performed during the active VBR job. Current private log captures are
under `/tmp/veeam-nfs-baseline` and sanitized request traces under
`/tmp/veeam-nfs-baseline-job-trace-*.json`.

### First real VBR NFS backup completed

User requested waiting through this run's polling window and will replace
environment packages for the next run. No packages or services were changed.

- At 19:03:31 UTC, worker snapshot polling timed out. VBR then connected to
  the worker service/core, verified backup-server and virtualization-manager
  connectivity, and marked the worker Ready at 19:03:34 UTC.
- Actual source backup started through POST `/vms/{vm}/backups` at 19:03:43.
  CloudStack StartBackupCmd succeeded. Backup UUID:
  `800172d7-cf2a-4cda-a6c4-08e6d7f836c3`.
- VBR logs: from checkpoint N/A, to `ckp-3880542b`; disk format Cow;
  `can be backed up incrementally: False`; extent retrieval with dirty=False
  succeeded. This is a baseline full transfer, not incremental proof.
- Image-transfer Download creation returned HTTP201; VBR fetched the PKI
  resource. Worker `.248` opened multiple TLS connections to host2 `.235:54322`.
  The image server started automatically. Normal per-request logging is DEBUG
  and was not enabled; do not claim individual GET/range/OPTIONS request
  details were captured at the image server. VBR logs establish successful
  extent retrieval and disk reading.
- Disk backup completed at 19:05:19 UTC. VBR reported processedDataSize
  8589934592, readDataSize 1903165440, transferredDataSize 680228122 bytes,
  progressPercent 100. Sparse/compressed transfer sizes do not prove CBT.
- Image-transfer finalize completed; VBR logged FinishedSuccess. Backup
  finalize completed at 19:05:29 UTC, with new checkpoint `ckp-3880542b`.
- VBR VM-task result at 19:05:55 UTC: Success; backup session
  `isSuccess: true`. Session contains the known worker-preparation snapshot
  warning. Job policy finished at the same time.
- Source VM remains Running on host2. CloudStack's wait-for-finalize job116
  completed successfully. The new checkpoint remains visible in libvirt.
  Worker was shut down by VBR; the image transfer was unregistered and the
  image server stopped automatically. No agent/service restart was performed
  by this investigation.

Fetched the final Windows job and VM log archives into the private directory
above (`nfs-test_2026-10-30_11-52-41.zip` and
`nfs-veeam-baseline-20261030184905_2026-10-30_12-03-34.2940507.zip`).
Get-VBRBackup sees backup `nfs-test`, but Get-VBRRestorePoint rejects this
backup type with "encrypted or created by an enterprise application plug-in".
That generic cmdlet rejection does not establish encryption or corruption.
The worker log's earlier ignored/corrupted point was seen before the new
backup completed; final restore-point integrity has not been established.

**Validated:** one real VBR baseline NFS disk backup, transfer and finalize,
successful VM-task result with the worker snapshot warning.
**Outstanding:** restore-point inspection through the VBR console, restore to
a new VM, guest boot/content verification, and any incremental chain test.
Do not claim recoverability or genuine incrementals from this run alone.

### Second VBR run: checkpoint reuse and dirty-extents transfer observed

User started an incremental run at 20:10:05 UTC. Job/policy is still
`nfs-test` / `bc0f7abf-72fe-4b8b-b474-19e66e7c8fa0`.
Session UUID: `02973681-d1cf-463e-b52c-5d23ce781fd7`.
VM-task UUID: `93c63ec5-6d92-48f3-8d21-ff148802eca0`.
User was asked whether guest data was deliberately changed; no answer had
arrived at collection time. Do not assume a controlled guest workload.

The worker snapshot path now succeeds: VBR created worker snapshot
`ea15364b-23c8-4435-9b53-a07487859ef4` at 20:10:59 UTC, reverted it after
an external update-package download failure, then deleted it successfully
at 20:11:13 UTC. Worker preparation resumed and passed. Package replacement
was handled by the user between runs; this monitoring did not deploy packages
or restart services. Do not attribute exact deployed binaries without checking.

Final VM archive proves these protocol events:

- Backup creation used previous checkpoint `ckp-3880542b` at 20:12:00 UTC.
- Disk transfer logged from checkpoint `ckp-3880542b` to `ckp-c388e73b`.
- Disk format Cow, `can be backed up incrementally: True`.
- Extent retrieval requested `dirty: True` (first run requested False).
- Disk statistics: processedDataSize 8589934592 bytes, readDataSize
  478150656 bytes, transferredDataSize 158338078 bytes, progressPercent 100.
- Image transfer finalized at 20:12:33 UTC. Backup
  `a40075d4-89a4-4e0d-b73a-b1f68fd9b64e` finalized at 20:12:43 UTC.
- VM session `isSuccess: true`, new checkpoint `ckp-c388e73b`.
- VM task result at 20:13:21 UTC: Success.

Compared with the first run, read bytes fell from 1903165440 to 478150656
and transferred bytes from 680228122 to 158338078. Checkpoint reuse plus
dirty-extents requests establish changed-block transfer behavior, not just
a smaller backup file. This still does not establish guest content correctness
or end-to-end incremental recoverability: restore from the second restore
point and verify guest content before claiming a validated incremental chain.
No LINSTOR backup/incremental compatibility is inferred from NFS evidence.

Private final archives under `/tmp/veeam-nfs-baseline`:
`nfs-test_2026-10-30_13-10-05.zip` and
`nfs-veeam-baseline-20261030184905_2026-10-30_13-11-50.2754777.zip`.
Sanitized control traces: `/tmp/veeam-nfs-incremental-trace-*.json`.

### Restore of the incremental point completed

VBR restored point `b720955d-30ba-40b4-b87c-c002c269654b`, explicitly
reported as `vmb_restore_point_incremental`, `isCorrupted=false`.
Restore task `e4d74308-74fb-4add-9ed2-43494c143c6e` completed Success;
the restored VM is `51b48121-d328-401e-bc40-13c0353fc301` (CloudStack 15),
with new NFS volume `1a83617a-8951-4410-8382-70e0e5c9ffc9`.
The NIC authorization failure was expected: the service account could not
use the admin-owned network. The user supplied a service-user network,
started the VM and confirmed it booted. Guest ping and SSH responded.

Read-only source/restored inspection matched SHA256 for
`/etc/redhat-release`, `/bin/bash`, `/usr/sbin/sshd`, `/etc/passwd` and
`/etc/ssh/ssh_host_rsa_key.pub`. Private evidence is
`/tmp/veeam-nfs-baseline/restore-content-check.json`.
This validates the observed NFS incremental restore and selected files;
no controlled guest mutation dataset was established. Earlier outstanding
restore statements above describe the state at collection time and are now
resolved. LINSTOR full/incremental compatibility remains unproven.
The user requested that NFS validation stop; use this baseline for implementation.

### LINSTOR full backup and new-VM restore passed

On 2026-10-31, after the user installed the updated packages, VBR 13.1.0.308
with oVirt plugin 13.8.0.281 completed a full backup of
`linstor-validation` (CloudStack VM 3, disk ROOT-3). The VBR VM-task log says
`Disk backup complete 1/1`; the Veeam agent session finished successfully.
The 8 GiB disk reported `format: Raw`,
`can be backed up incrementally: False`, `dirty: False`, and no checkpoint
(`ToCheckpointId` null). Veeam reported 8,589,934,592 bytes read and
749,644,736 bytes transferred. This confirms the raw-size export fix and
successful full transfer, but explicitly confirms that this full point does
not provide an incremental baseline.

The user then completed a VBR restore to the new VM
`linstor-validation-restored` (CloudStack VM 17). The target root volume
`b1056e36-1815-4549-a016-09bf19cba956` reached `Ready` on LINSTOR-POC;
the image transfer finalized and the disk attachment was created. The user
confirmed that the restored VM booted. The upload used the writable,
size-bounded 8 GiB raw NBD export. The temporary image transfer and NBD
process ended, and no `cs-vbr-*` backup resource or snapshot remained. The
deployment health check passed after this run. No selected-file hash
comparison was recorded for the LINSTOR restore.

The LINSTOR VBR full path is accepted for this single-disk lab case. This
earlier raw-format run had no checkpoint and was not incrementally eligible.
The later QCOW2 baseline and incremental run are recorded below.

### LINSTOR QCOW2 baseline and first incremental run

On 2026-10-31 the global `kvm.backup.linstor.incremental.enabled` setting was
enabled. The VBR UI initially classified a run as a full scan; the preceding
full-only backup had no checkpoint lineage. The subsequent baseline used the
incremental-capable path: the oVirt-compatible disk endpoint advertised
`format: cow` (QCOW2), and CloudStack created a full backup with a new
`to_checkpoint_id` and no `from_checkpoint_id`.

Baseline backup `79174bbc-bfd3-40d8-92d5-dc9e1badf40f` was created at
16:41:00 UTC. Its image transfer and backup finalized successfully at
16:43:52 UTC; CloudStack marked it `BackedUp` and retained active checkpoint
`3a9312e8-e03e-419c-bef9-0f6f5df9e757`.
The VBR session reports 8,589,934,592 bytes read and 1,207,842,294 bytes
transferred for the full baseline.

The next backup, `31cedb1c-df3b-47cf-bef7-67801a00b1c9`, was created at
16:47:13 UTC. The user confirmed that VBR displayed it as **incremental**.
CloudStack recorded type `INCREMENTAL`, with `from_checkpoint_id`
`3a9312e8-e03e-419c-bef9-0f6f5df9e757` and new `to_checkpoint_id`
`446f6697-8b82-4484-b745-da1d8f505fba`. Image-transfer finalization and backup
finalization returned HTTP 200 at 16:48:00 UTC; CloudStack marked the backup
`BackedUp` and advanced the active checkpoint to the new ID. The older
checkpoint became the retained previous checkpoint.

The Windows VBR backup archive confirms `format: Cow`,
`can be backed up incrementally: True`, `dirty: True`, and the same from/to
checkpoint IDs. It reports 544,210,944 bytes read and 528,379,776 bytes
transferred out of 8,589,934,592 processed bytes. Private archive:
`/tmp/linstor-incremental-backup-vbr.zip`. Relative to the full baseline,
the incremental transferred 56.3% fewer bytes and read 93.7% fewer bytes.

This is live evidence that VBR selected an incremental point and that the
checkpoint chain advanced. The sanitized control trace is
`/tmp/veeam-trace-4ab47b2c479e.json`; DB state was checked read-only on the
management server. The user initiated a new-VM restore of the incremental
point to verify a test file created before the incremental run. Image upload
finalized at 16:53:14 UTC; target VM
`linstor-incremental-restored` (`598c129f-1665-4178-a069-96fd2b499bd2`) is
stopped and its LINSTOR root volume `7128ecab-2551-4e5d-801c-4a61345c3382` is
`Ready`.

VBR restore archive `/tmp/linstor-incremental-restore-vbr.zip` confirms it
selected restore point `0f05b53c-57e8-48fd-956b-7085720645b2`, type
`vmb_restore_point_incremental`, creation time 16:47:07 UTC, with
`isCorrupted=false`. Veeam opened both the incremental
`linstor-incremental Backup_2026-10-31T094711.vib` and baseline
`linstor-incremental Backup_2026-10-31T094058.vbk`. The restore task reported
Success and the upload finalized. The user initially reported the expected
test file absent from the first restore target, so they repeated the restore.
VBR also logged a `NullReferenceException` while attempting to reduce the
restored disk; its effect was not established.

The user repeated the restore and explicitly selected the second restore point.
The second VBR archive independently confirms the same point ID
`0f05b53c-57e8-48fd-956b-7085720645b2`, type
`vmb_restore_point_incremental`, creation time 16:47:07 UTC, and
`isCorrupted=false`. Veeam again opened the incremental `.vib` together with
the baseline `.vbk`, loaded data regions, and reported task `57c07b5c-4253-4fab-930a-77d5fe19a5b1` Success. Its upload finalized and the disk attached to
`linstor-incremental11` (`cc81a933-1bf4-4088-a6ad-aef1ce8283df`); the root
volume is `Ready`. This second attempt logged the same disk-reduction
`NullReferenceException`. Private archive:
`/tmp/linstor-incremental-restore2-vbr.zip`.

The user booted `linstor-incremental11` and compared `testfile.img` on the
original VM and restored VM. Both reported MD5
`0a3835886f78638ce7000704f0719ca5`.

An additional whole-volume comparison used the retained, successful LINSTOR
checkpoint `cs-vbr-446f66978b824484b745da1d8f505fba`, which represents the
incremental point and is not attached to a VM. SHA-256 of its first logical
8 GiB matched SHA-256 of the preserved pre-boot restored image:
`4539ad23069a358e36ead3cffff7b561be3247b45c9cb8db209e12aad66fe2dd`.
The preserved compressed image is `/tmp/linstor-incremental11-root.raw.gz`;
its uncompressed size is exactly 8 GiB and its gzip integrity check passed.
This confirms the second restore contains the incremental-point disk contents;
the first restore's missing-file observation was not reproduced or explained.

Incremental-point restore content is validated for this lab VM. VBR's repeated
disk-reduction warning remains unexplained. A full-versus-incremental transfer
byte comparison is complete. Broader failure/recovery testing remains
outstanding. The first restore target was later observed Running, so its
earlier missing-file report was not diagnosed from its live disk; the second
restore independently matched the checkpoint and test-file hashes.
