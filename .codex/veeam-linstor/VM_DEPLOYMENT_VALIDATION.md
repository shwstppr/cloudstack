# CloudStack VM deployment validation

For repeat checks use [checks/README.md](checks/README.md) and
`python3 .codex/veeam-linstor/checks/deployment.py`; add `--mode deploy --cycle`
to verify a new deployment and cold restart onto the other host. Scripts
produce a short summary and a separate JSON report, so this investigation
does not need to be repeated.

On 2026-10-30 the user explicitly requested proving a VM deployment works
on LINSTOR. No manual user intervention was needed.

## Retained lab objects

| Object | Value |
| --- | --- |
| VM name / display name | `linstor-validation` / `LINSTOR validation VM` |
| VM UUID | `3b89203c-2667-4c76-9e10-4d85583e30d3` |
| Libvirt instance name | `i-2-3-VM` |
| Template | Built-in CentOS 5.5 (64-bit), existing ready lab template |
| Service offering | `LINSTOR-Validation`, 1 vCPU, 512 MiB RAM, storage tag `linstor-poc` |
| Offering UUID | `c96fce7a-a94d-4858-a887-113c0c7332b9` |
| Root volume | `ROOT-3`, 8 GiB, UUID `4e484f68-8fe9-4c45-9070-1d9f8cbefe64` |
| Root primary storage | `LINSTOR-POC`, pool ID 5, type `Linstor` |
| Network | `linstor-validation-net`, UUID `2679f495-e915-4ed7-ad1c-247aeee73cac` |
| Guest address | `10.1.1.229/24`, gateway `10.1.1.1` |
| Router | `r-4-VM`, control IP `169.254.124.171`, public IP `172.120.6.3` |

The offering's `storagetags=linstor-poc` constrains root storage placement.
No changes were made to existing offerings. New test objects are retained
for inspection instead of deleting the evidence.

## Initial deployment on host2

Deploy job `94ae779a-5e53-4f51-900d-d7fe6d10846f` completed successfully
(`jobstatus=1`) at 2026-10-30 12:12:21 UTC. CloudStack returned:

- State `Running`, hostname `qa6-beyond-nfs-e29e5aeb-kvm-host2`.
- `pooltype=Linstor`.
- Root volume `Ready` on `LINSTOR-POC`, 8589934592 bytes.

Libvirt confirmed the running domain's `vda` is the block device:

```text
/dev/drbd/by-res/cs-4e484f68-8fe9-4c45-9070-1d9f8cbefe64/0
```

Viewed a console screenshot: the kernel discovered `vda1`/`vda2`, mounted
the root filesystem and entered CentOS service startup. Network interface
DHCP completed and services showed `[ OK ]`. The guest answered three
ICMP requests from the virtual router with zero loss, and a TCP connection
to guest SSH port 22 succeeded. Libvirt reported guest disk reads and writes.

The deployment also created virtual router `r-4-VM` with its root disk on
LINSTOR, using the existing SystemVM template. Original `s-2-VM` and
`v-1-VM` were unaffected. LINSTOR now retains template-cache resources and
the new running VM/router resources; these are intentional, not orphaned
storage-test resources.

## Stop/start onto host1

CloudStack `stopVirtualMachine` job
`95bd3025-5c23-4666-804a-ffecea3c00c3` succeeded, returning `Stopped`.
Then `startVirtualMachine` explicitly targeted host1 UUID
`08168acf-abc6-4728-a0da-deb12881b66d`.

Start job `526d6251-a0c2-4e40-8bc0-37d6b1aba8c0` succeeded and returned
`Running` on `qa6-beyond-nfs-e29e5aeb-kvm-host1`, still `pooltype=Linstor`.
Libvirt on host1 showed the same root DRBD device and LINSTOR reported it
as a Primary diskless client with the appliance peer `UpToDate`. Root
volume UUID, storage pool and path remained unchanged.

After this second boot the guest again answered ICMP and opened TCP port 22.
Directly viewed the host1 console screenshot: it shows CentOS release 5.5,
kernel `2.6.18-194.15.el5`, and the `linstor-validation login:` prompt.
Libvirt recorded approximately 146 MB read and 11 MB written during this
boot. This confirms OS startup on the same LINSTOR root disk from both hosts,
not just CloudStack's `Running` state. The VM is left running on host1.

## Evidence and limits

API helper scripts, object IDs and complete asynchronous job results are
under `/root/linstor-provisioning/` on management, including
`deployment-test.json`, `deployment-job-result.json`,
`stopVirtualMachine-result.json` and `startVirtualMachine-result.json`.
Console captures were taken from libvirt for direct boot inspection. The
final login-screen image is `linstor-validation-host1.png` in the same root
evidence directory on management (also `/tmp/linstor-guest-host1.png` in the
local agent workspace).

This tests a fresh VM root-disk deployment and a cold restart onto another
host. It does not test live migration, CloudStack VM snapshots, guest
application consistency, or Veeam backup/restore. The legacy CentOS template
is used only as the already available lab boot test image.
