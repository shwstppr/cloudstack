# LINSTOR appliance provisioning

**Current appliance:** Ubuntu at `172.120.0.166` is operational and validated.
See [UBUNTU_APPLIANCE.md](UBUNTU_APPLIANCE.md) for current configuration,
test evidence, and CloudStack KVM integration preparation. The AlmaLinux
notes below are historical; work on `172.120.0.52` was discontinued when
the user chose public Ubuntu packages. Its DRBD 9.2.20 module was installed
and loaded before switching; LINSTOR server was not operational there.

## Authorization and scope

On 2026-10-30 the user approved installation on `root@172.120.0.52`
using configured SSH keys, explicitly requiring use of the existing
unpartitioned space and no additional virtual disk. This overrides the
APPLIANCE_SETUP.md prohibition on repartitioning only to permit adding
a data partition in previously unused sectors. Existing OS partitions,
filesystems, and LVs must not be resized or reformatted.

## Inspection and storage changes

- AlmaLinux 8.7, kernel `4.18.0-425.10.1.el8_7.x86_64`, two vCPUs,
  approximately 3.6 GiB RAM. Controller and satellite initially absent.
- One 100 GiB disk with DOS partition table.
- `/dev/sda1`: start 2048, length 2097152 sectors, XFS `/boot`; unchanged.
- `/dev/sda2`: start 2099200, length 65009664 sectors, VG `almalinux`;
  root 27.79 GiB and swap 3.20 GiB; unchanged. No free VG extents.
- Created `/dev/sda3` at sector 67108864, length 142606336 sectors
  (68 GiB), exclusively in the previously unpartitioned tail.
- Created PV `/dev/sda3`, VG `linstor_vg`, thin pool `thinpool` with
  60 GiB data, 256 MiB metadata and metadata spare, 64 KiB chunks.
  Approximately 7.5 GiB remains free in this VG.
- Partition table backup and original LVM config are under
  `/root/linstor-provisioning/` on the appliance.
- `partx --add --nr 3 /dev/sda` registered the new partition without reboot.

## Installation in progress

AlmaLinux's current official signing key was imported using its documented
key rotation procedure; RPM signature verification remains enabled.
Installed DRBD userspace 9.28.0 from EPEL, Java 21 and build dependencies
from AlmaLinux. Installed LINSTOR CLI 1.23.2 and Python API 1.23.1 from
LINBIT source tarballs. LINSTOR server 1.35.3 and DRBD module 9.2.20
are being built from LINBIT release tarballs. Matching kernel-devel
was downloaded from the AlmaLinux 8.7 vault and passed RPM signature checks.

Remaining: finish builds, configure services and local API access, register
combined node and LVM-thin pool, verify DRBD resource IO and snapshot restore,
verify service restart, create CloudStack resource group, and document KVM
host prerequisites. No CloudStack or Veeam code changes are authorized by
this appliance provisioning step.

## Intended validation

Use disposable small resources only. Write deterministic data, record hashes,
take a LINSTOR snapshot, overwrite source data, restore the snapshot to a
separate resource, and verify original hashes. Delete only these disposable
test resources and snapshots after success. Confirm pool remains healthy.

This is a single storage node lab with no replicated redundancy. KVM hosts
will need DRBD 9 and LINSTOR satellite, matching LINSTOR node/host identities,
controller reachability, and DRBD data-plane reachability. Host details have
not been provided; do not modify other hosts or CloudStack yet.
