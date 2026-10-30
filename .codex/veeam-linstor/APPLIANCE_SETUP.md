## Single-disk MBX appliance

The appliance has one 100 GB virtual disk shared by
AlmaLinux 8 and LINSTOR.

Codex must:

1. Connect to the appliance over SSH.
2. Inspect disk partitions, filesystems and LVM.
3. Determine whether unallocated LVM extents exist.
4. Never format, repartition or shrink the OS disk.
5. Prefer an LVM-thin pool using existing free extents.
6. Reserve sufficient capacity for the operating system.
7. Stop and request approval if storage layout changes
   are necessary.
8. Verify LINSTOR snapshots and resource provisioning.

Do not use loopback-backed storage for the primary
incremental-backup proof of concept without explicit
approval.

Do not begin CloudStack or Veeam integration until
the storage pool has been validated.
