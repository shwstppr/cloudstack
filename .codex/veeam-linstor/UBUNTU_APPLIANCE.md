# Validated Ubuntu LINSTOR appliance

Validated 2026-10-30. User authorized installing/configuring LINSTOR and
testing storage using only unpartitioned space on the existing VM disk.
No additional virtual disk was attached. No Veeam development performed.

## Access and installed software

- SSH: `linstor@172.120.0.166`, configured SSH keys, sudo required.
  Credentials are deliberately omitted from this document.
- Hostname/LINSTOR node: `linstor-controller` (COMBINED).
- Ubuntu 24.04.5 LTS; kernel `6.8.0-147-generic`; approximately 3.8 GiB RAM.
- Repository: public `ppa:linbit/linbit-drbd9-stack`, Ubuntu noble.
- Controller and satellite: `1.35.3-1ppa1~noble1`.
- Client: `1.29.1-1ppa1~noble1`.
- DRBD DKMS: `9.3.4-1ppa1~noble1`; utils: `9.35.0-1ppa1~noble1`.
- DKMS reports the module installed for the running kernel. DRBD 9.3.4
  and its TCP transport loaded successfully. Module loading is configured
  in `/etc/modules-load.d/linstor-drbd.conf`.
- Controller and satellite services are active and enabled at boot.

The Ubuntu bundled DRBD 8.4 module loaded during initial package installation.
With no resources present, stopped satellite, unloaded that unused module,
loaded the packaged DRBD 9 module/TCP transport, and restarted satellite.
No reboot was needed. A full reboot has not been tested.

## Disk and storage pool

The only disk is 100 GiB `/dev/vda`, GPT. Existing partitions are unchanged:

| Partition | Start sector | Length sectors | Purpose |
| --- | ---: | ---: | --- |
| vda1 | 2048 | 2048 | BIOS GRUB |
| vda2 | 4096 | 67108864 | ext4 root, 32 GiB |
| vda3 (new) | 67112960 | 142600192 | LINSTOR PV, about 68 GiB |

- PV `/dev/vda3`, VG `linstor_vg`.
- LVM-thin pool `linstor_vg/thinpool`: 60 GiB, 64 KiB chunks,
  256 MiB metadata plus metadata spare. Approximately 7.5 GiB VG free.
- LVM monitoring active; final data usage 0.00%, metadata 6.36%.
- LVM global filter rejects DRBD devices and LINSTOR mapper devices.
- LINSTOR storage pool: `cloudstack-thin`, provider `LVM_THIN`, status `Ok`,
  snapshot capability `True`, 60 GiB total/free after test cleanup.
- Resource group `cloudstack-rg`: `DRBD,STORAGE`, one diskful replica,
  storage pool selection `cloudstack-thin`; volume group 0 configured.
- Single storage node: no replicated redundancy or HA.

## Validation evidence

1. Spawned disposable 256 MiB `provisioning-test` through `cloudstack-rg`.
   LINSTOR reported DRBD `UpToDate` and a `/dev/drbd/by-res/.../0` device.
2. Wrote and flushed 64 MiB of deterministic data through the DRBD device.
3. Took LINSTOR snapshot `baseline`; state `Successful`. LINSTOR reported
   suspend, LVM-thin snapshot creation, and IO resume.
4. Overwrote and flushed the same source region; confirmed hash changed.
5. Restored snapshot volume definitions and resource into separate
   `provisioning-restore`. Marked its DRBD device read-only and verified
   the first 64 MiB matched the baseline.
6. Restarted controller and satellite with both resources present. Node
   returned `Online`; restored and modified-source hashes still matched
   their respective expected values.
7. Removed read-only flag for cleanup; deleted only the disposable restore,
   snapshot, and source resource definitions. Resource/snapshot lists empty.
   LINSTOR's own small `.probeVolumeThinProv` LV may remain; leave it managed
   by LINSTOR.

SHA256 for tested 64 MiB region:

| Data | SHA256 |
| --- | --- |
| Baseline / restored / restored after restart | `b5cafb87eefaab98279543f18ecfcc80453b111d1ad6d9cb7dfe63b73ccd64fd` |
| Modified source / source after restart | `1c5386005b9cc63833a7cac9ee928040d05d4128b81715d893f2e2fb2a3391b7` |

This proves provisioning, raw block IO, snapshot isolation, restore into a
separate resource, service restart persistence and cleanup. It does not
prove filesystem/application consistency, VM bootability, network replication,
CloudStack interoperability, CBT, or Veeam incremental support.

Logs, hashes, validation script, partition table backup, original LVM config,
and validated controller config are in `/root/linstor-provisioning/` on the
appliance (root access only). `validation.log` ends `VALIDATION_COMPLETE`.
Controller database remains `/var/lib/linstor/linstordb` (H2).

## API access and CloudStack KVM preparation

Controller HTTP remains local at `127.0.0.1:3370`. During KVM integration,
enabled LINSTOR native HTTPS and token authentication: the remote API is
`https://172.120.0.166:3371`, with a self-signed certificate explicitly
trusted by management and both KVM agents. Unauthenticated API requests
return 401. Satellite listens on TCP 3366 and is registered at
`172.120.0.166`. Firewalls remain inactive; satellite/DRBD connections use
the lab network. This is not a production hardened deployment.

Both KVM hosts are now registered as diskless satellite clients; the
CloudStack `LINSTOR-POC` pool is Up on both. See
[KVM_INTEGRATION_STATUS.md](KVM_INTEGRATION_STATUS.md) for configuration,
cross-host snapshot/IO evidence, source-build maintenance, and remaining
VM-level validation. The preparation sequence below records the original
plan; it is not a list of entirely outstanding work.

For administrative CLI access now:

```sh
ssh linstor@172.120.0.166 'linstor node list; linstor storage-pool list'
```

Before connecting CloudStack:

1. Obtain management-server and KVM-host addresses, SSH access, and target
   zone/cluster. Inspect host OS, kernel, existing DRBD, hostname and CloudStack
   agent storage plugins before proposing host changes.
2. Install compatible DRBD 9 and LINSTOR satellite on KVM hosts; register each
   using the identity expected by the CloudStack KVM adaptor. Hosts may be
   diskless clients, so no additional backing storage on them is required.
3. Validate controller-to-satellite TCP 3366 reachability and DRBD TCP data
   paths (normally the allocated 7000+ resource ports) between diskful storage
   and diskless KVM hosts. Do not assume guest/host reachability.
4. Configure a trusted HTTPS controller endpoint and API authentication with
   restricted network access before exposing the API for integration. The
   current HTTP loopback endpoint is not accessible from management/KVM hosts.
5. Add primary storage using provider/type `Linstor`, the secured controller
   URL, and detail `resourceGroup=cloudstack-rg`. Pool capacity is 60 GiB;
   place-count 1 is intentional for this single-node lab.
6. Test diskless resource attachment and IO from a KVM host, then a disposable
   CloudStack volume/VM, snapshot/restore, and VM boot. Do not move existing
   workloads or modify NFS paths for initial testing.

The current checked-out repository contains LINSTOR integration under
`plugins/storage/volume/linstor`, with provider name `Linstor`, detail key
`resourceGroup`, and per-pool `lin.auth.apitoken`/`lin.ssl.insecure` config.
Use trusted certificate verification (`lin.ssl.insecure=false`) when setting
up the secured endpoint. No CloudStack source changes were made.

Veeam development remains behind the separate protocol feasibility gate
described in the handover documents; appliance snapshot success alone does
not establish Veeam backup or incremental interoperability.
