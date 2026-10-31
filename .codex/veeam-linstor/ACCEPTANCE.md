# Acceptance criteria and demo plan

## Lab prerequisites
- CloudStack MS, KVM host, working Veeam VBR/proxy, standalone Ubuntu LINSTOR controller/storage appliance and AlmaLinux 8 diskless KVM satellites (current lab).
- Explicitly validate whether 100GB disk is controller-only or usable data capacity. Controller-only VM does not supply a functional DRBD-backed storage pool.
- Confirm resource placement, network reachability, supported LINSTOR driver, sufficient free disk, and backup repository space.

## Full backup
- [x] VBR discovers the LINSTOR VM and disk.
- [x] Full backup finishes without requiring NFS primary storage.
- [x] Restore to a *new* VM/volume; user confirmed the restored VM boots. A selected-file hash comparison was not recorded.
- Deleting/interrupting a backup does not corrupt source volume; temporary snapshots are cleaned.

## Incremental backup
- Establish full baseline; write small known changes and deletions; run incremental.
- Show that VBR identifies/stores a real incremental restore point, not merely another full backup.
- Show actual source bytes read and transferred versus full disk; explain metadata overhead.
- Restore to both full and incremental points; verify different expected dataset hashes.
- Validate a second incremental and a chain restore; force bitmap loss and confirm full fallback.

## Failure scenarios
- VBR proxy unreachable, controller unavailable, snapshot creation failure, midstream cancellation, storage exhaustion, expired token, KVM host restart, VM disk resize, interrupted incremental, chain metadata corruption.
- Ensure actionable errors, no silent corruption, and bounded cleanup/retry.

## Demo script
1. Show LINSTOR-backed VM and disk in CloudStack.
2. Run Veeam full backup; show job completion and volume transport details.
3. Modify a known 1–2GB data set; run incremental; display transferred bytes and restore point type.
4. Restore full and incremental points separately; verify content and boot.
5. Explain limitations, protocol compatibility and roadmap.

If incremental interoperability is blocked, present only validated full-backup capability and label changed-block experiment separately.
