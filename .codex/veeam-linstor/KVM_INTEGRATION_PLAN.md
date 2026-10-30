# qa6-beyond-nfs LINSTOR integration plan

Initial read-only inventory on 2026-10-30. The user subsequently approved
option 1 (source installation). Both KVM satellites are now installed and
validated, and the CloudStack primary pool is Up on both hosts. See
[KVM_INTEGRATION_STATUS.md](KVM_INTEGRATION_STATUS.md) for execution results.
The inventory and proposed sequence below describe the starting point.

## Confirmed environment

Setup: `qa6-beyond-nfs-e29e5aeb-kvm`.

| Role | Hostname | Address | State |
| --- | --- | --- | --- |
| Management | qa6-beyond-nfs-e29e5aeb-kvm-mgmt1 | 172.120.0.107 | management active |
| KVM 1 | qa6-beyond-nfs-e29e5aeb-kvm-host1 | 172.120.0.227 | agent active, CloudStack Up |
| KVM 2 | qa6-beyond-nfs-e29e5aeb-kvm-host2 | 172.120.0.235 | agent active, CloudStack Up |
| LINSTOR diskful/controller | linstor-controller | 172.120.0.166 | Online, validated |

Root SSH key access works on all three CloudStack machines. All run AlmaLinux
8.7 with kernel `4.18.0-425.10.1.el8_7.x86_64`. KVM hosts have Java 17,
approximately 7.6 GiB RAM and 24 GiB free root filesystem space. Neither
host has LINSTOR satellite or DRBD installed; no Docker/Podman found.
Both host disks are 32 GiB OS disks; no host backing-storage changes needed.

CloudStack packages are `24.0.0-1`. Both agents and management already ship
`cloud-plugin-storage-volume-linstor-24.0.0-SNAPSHOT.jar` and
`java-linstor-0.7.0.jar`. Presence does not prove full deployed source parity
or successful runtime integration.

- Zone `DC`: ID 1, UUID `af95c50a-a7fc-4c3c-a098-cb348ed123e1`.
- Cluster `Cluster`: ID 1, UUID `e0452ab0-131b-45bb-a5bc-dc8276001227`, KVM.
- Two NFS primary pools, `Primary1` and `Primary2`.
- Host1 runs system VM `s-2-VM`; host2 runs `v-1-VM`.
- All three CloudStack nodes resolve `linstor-controller` to 172.120.0.166
  and successfully establish TCP to appliance satellite port 3366.
- Host firewall inactive; SELinux permissive (as found).
- Controller HTTP 3370 remains loopback-only. No remote API connectivity
  or DRBD data-plane connectivity has yet been validated.

## Installation-method decision

User moved the appliance to Ubuntu to use public prebuilt packages. The
KVM hosts are EL8, so their LINSTOR satellite and DRBD 9 installation still
needs one of:

1. Existing hosts: build and install compatible LINSTOR satellite and DRBD
   from LINBIT sources. Record versions, install paths and service units,
   isolate the satellite Java runtime from the CloudStack agent Java 17,
   and retain module rebuild instructions for kernel updates.
2. Existing hosts: use LINBIT customer/evaluation EL8 RPM repository access.
3. User redeploys KVM hosts on Ubuntu, then use public PPA packages and DKMS.
   Redeployment would affect running system VMs and must be user-managed.

Asked user to select a route before host installation. No host packages,
services, CloudStack pools or workloads have been changed by this inventory.

## Concrete execution sequence after decision

1. Inspect package availability/kernel requirements and installed agent
   hostname behavior. Back up relevant host configuration. Install the
   chosen satellite/module runtime without OS disk changes, NFS changes,
   or restarting libvirt/agents unless necessary and reviewed.
2. Load DRBD 9 and TCP transport; verify module version and compatibility
   with appliance DRBD 9.3.4. Configure persistent loading. Register the
   two satellites with exact hostnames expected by `LinstorStoragePool`.
   They are diskless clients; only the Ubuntu appliance holds diskful data.
3. Configure controller HTTPS and authentication with trusted certificates
   on management and KVM hosts, restricting API/network access to the lab
   participants. Agent token fallback uses `/var/lib/linstor.d/auth.json`
   in current repository code; verify deployed behavior before configuring.
4. Verify controller-to-host TCP 3366 and host/appliance DRBD TCP resource
   ports (currently allocated from 7000) bidirectionally. Record actual
   allocated ports and routing; do not assume guest access to host networks.
5. Spawn a disposable small volume from `cloudstack-rg`, attach it diskless
   on a KVM host, write/read known data, and test snapshot restoration.
   Move access between hosts with explicit demotion/closed-device checks;
   never mount the same filesystem concurrently on both hosts.
6. Add cluster-scoped primary storage through the CloudStack API with
   provider/type `Linstor`, secured controller URL and detail
   `resourceGroup=cloudstack-rg`. Retain one diskful copy and the 60 GiB pool.
   Use `lin.ssl.insecure=false` with trusted TLS. Verify credentials never
   enter logs or this handover documentation.
7. Create a disposable CloudStack volume/VM, test disk provisioning and
   attachment, boot, snapshot/restore and relevant host movement, and compare
   expected data. Clean up only disposable test objects. Preserve existing
   NFS primary pools and system VMs.

No Veeam development follows automatically from these storage checks. The
protocol feasibility gate and separate full/incremental restore evidence
requirements in the handover still apply.
