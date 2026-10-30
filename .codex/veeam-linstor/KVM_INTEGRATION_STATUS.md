# KVM LINSTOR integration results

Completed source-install attempt 1 on 2026-10-30, explicitly approved by
the user because no Ubuntu MBX host template is available.

## Result

- Both AlmaLinux KVM hosts have LINSTOR satellite 1.35.3 built from LINBIT's
  release source distribution and DRBD 9.2.20 built for their running
  `4.18.0-425.10.1.el8_7.x86_64` kernel.
- All three LINSTOR nodes are Online: Ubuntu `linstor-controller`,
  `qa6-beyond-nfs-e29e5aeb-kvm-host1` (172.120.0.227), and
  `qa6-beyond-nfs-e29e5aeb-kvm-host2` (172.120.0.235).
- KVM satellites are diskless clients. The Ubuntu appliance remains the
  only diskful storage node. No virtual disks or host storage pools added.
- CloudStack primary pool `LINSTOR-POC`, UUID
  `bc81d300-94af-4cd7-9e3c-c2e277e6e170`, database ID 5, type/provider
  `Linstor`, is Up and has host associations for both host IDs 1 and 2.
- Zone `DC`, cluster `Cluster`, cluster scope; capacity 64424509440 bytes
  (60 GiB), resource group `cloudstack-rg`, tag `linstor-poc`.
- Controller URL `https://172.120.0.166:3371`;
  pool detail `lin.ssl.insecure=false`.
- Existing NFS pools `Primary1` and `Primary2` remain Up. System VM
  `s-2-VM` on host1 and `v-1-VM` on host2 remained running throughout checks.
- No CloudStack or Veeam source code changes made.

## Installation and runtime isolation

Both hosts:

- Satellite runtime `/opt/linstor-satellite-1.35.3`.
- Unit `/etc/systemd/system/linstor-satellite.service`, enabled and active.
- Satellite-only `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, maximum heap 1 GiB
  through `JAVA_OPTS` (last heap setting overrides distribution default).
- CloudStack agent continues using its original Java 17 binary and service.
- DRBD module installed under the running kernel's `updates` directory;
  DRBD core and TCP transport load successfully.
- `/etc/modules-load.d/linstor-drbd.conf` persists core/TCP module loading.
- `/etc/drbd.d/linstor-resources.res` includes `/var/lib/linstor.d/*.res`.
- EPEL `drbd-utils` and `drbd-udev` installed. The udev package is required
  for `/dev/drbd/by-res/<resource>/0` links, and rules were reloaded.
- Imported the official current AlmaLinux signing key to install signed
  build dependencies. No package signature verification disabled.
- Source archives, original Java path, dependency/build logs, and
  configuration backups are under `/root/linstor-provisioning/`.

LINSTOR build used verified Gradle 8.14.2, Java 21 and the release archive's
offline dependency cache. Disabled Error Prone for this lab build and skipped
`server:generateProto`/`server:generateJava` because the published release
archive already includes generated sources; no source files were edited.
Both builds reported `BUILD SUCCESSFUL`.

```sh
cd /root/linstor-provisioning/sources/linstor-server-1.35.3
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  /opt/gradle-8.14.2/bin/gradle --no-daemon --max-workers=2 \
  --gradle-user-home .gradlehome --offline -PdisableErrorProne \
  -x :server:generateProto -x :server:generateJava satellite:installDist
```

## Cross-node validation

Disposable 256 MiB resource `kvm-connectivity-test` was spawned on the Ubuntu
LVM-thin pool and attached diskless to host1. DRBD reported an established
connection and the appliance's peer disk `UpToDate`.

1. Wrote and flushed 16 MiB through host1's DRBD device; the appliance read
   exactly the same checksum.
2. Took LINSTOR snapshot `host-baseline` while the diskless host was connected.
3. Restored into separate diskful resource `kvm-connectivity-restore`.
4. Overwrote the source's first 16 MiB with zeros through host1.
5. Attached source and restored resources diskless to host2. Host2 read the
   zeroed-source checksum and original restored checksum respectively.
6. Wrote fresh random data through host2; host1 read that same new checksum.
7. Restarted each LINSTOR satellite; both returned Online. Removed only the
   disposable resources and snapshot; final resource/snapshot lists empty.

SHA256 of the first 16 MiB:

| Check | SHA256 |
| --- | --- |
| Host1 baseline, appliance read, host2 snapshot restore | `fd7e5884914ccb6a6e847e430da722ce6d8e47737dc6eb2076e982c4a91f3432` |
| Zeroed source, read on host2 | `080acf35a507ac9849cfcba47dc2ad83e01b75663a516279c8b9d243b719643e` |
| Host2 write, read on host1 | `a4e6a96872c1765125fc4b8e7c610d81029229da6571201847131dc87833fe16` |

This verifies mixed DRBD 9.2.20/9.3.4 protocol interoperability for these
operations, diskless reads/writes on both KVM hosts, and snapshot isolation
and restore across nodes. It is not a certification of every DRBD feature.

## HTTPS, credentials and certificate trust

Enabled native LINSTOR HTTPS/token authentication with
`linstor controller auth init`; credentials were captured only in protected
root files. The generated certificate includes DNS names localhost and
linstor-controller and IP 172.120.0.166. Certificate SHA256 fingerprint:

`BA:CD:F0:F9:76:1C:06:51:01:A4:AD:17:02:9F:6D:2B:CD:F2:07:6B:EF:9F:28:7E:DE:8E:26:E4:89:91:D6:DC`

- Unauthenticated remote request returns 401.
- Authenticated requests with explicit certificate verification return 200
  from management and both hosts.
- Management token is IP-filtered to 172.120.0.107 and stored in
  `/var/lib/linstor.d/auth.json`, root:cloud, mode 0640.
- Agents use controller-distributed satellite credentials in their existing
  `/var/lib/linstor.d/auth.json`; verified the deployed Java API supports
  fallback to that path. No raw tokens recorded in repository documents.
- The local `linstor` user's CLI has a protected token restricted to
  127.0.0.1 and HTTPS controller configuration. Root CLI is also configured
  for HTTPS. Existing administrative SSH access is unchanged.
- Imported public certificate alias `qa6-linstor-controller` into Java
  `/etc/pki/java/cacerts` on all three CloudStack nodes.
- KVM `LibvirtComputingResource.enableSSLForKvmAgent()` overrides Java's
  trust store with `/etc/cloudstack/agent/cloud.jks`. Added the same public
  certificate to that store, preserving existing entries. Backup:
  `/root/linstor-provisioning/cloud.jks.before-linstor` on each host.
- Restarted CloudStack agents sequentially to reload trust; both hosts
  reconnected Up. Libvirt and VMs were not restarted. Two initial pool
  attempts failed certificate verification and were removed by CloudStack;
  the successful pool is ID 5. Never disabled certificate verification.
- Management did not need a restart. Pool creation used the local loopback
  integration API and token-file fallback, without putting the token into
  request URLs. Pool details contain no explicit API token.

HTTP 3370 remains loopback-only; HTTPS 3371 listens remotely. Existing lab
firewalls remain inactive. Satellite control traffic uses plain TCP 3366;
DRBD data traffic uses its allocated resource ports (7000/7001 during tests).
This is a functional lab configuration, not full network hardening.

## Maintenance and remaining validation

- DRBD is source installed, **not DKMS managed** on EL8 hosts. Before booting
  a different kernel, build/install modules for that exact kernel and verify
  compatibility. Do not assume an OS/kernel update will preserve LINSTOR IO.
- Satellite upgrades are manual. Retain matching source archive, Gradle cache,
  versioned runtime and service configuration; avoid replacing Java 17 for
  the CloudStack agent.
- Java system trust extraction or CloudStack certificate/keystore renewal
  may remove the added certificate. Re-import the verified controller public
  certificate if those stores are regenerated. The host import helper is
  `/root/linstor-provisioning/import-agent-cert.py` (reads the passphrase
  locally without printing it); it currently expects a new alias, so check
  existing alias/certificate before reuse.
- HTTPS/token access works, but lab network allowlists, certificate renewal,
  token rotation/expiry policy and satellite TLS remain follow-up hardening.
- No full host/appliance reboot was performed. CloudStack guest deployment,
  LINSTOR root-disk attachment, OS boot and stop/start onto the other KVM
  host have now been tested; see [VM_DEPLOYMENT_VALIDATION.md](VM_DEPLOYMENT_VALIDATION.md).
  CloudStack VM snapshots and live migration have not been tested.
- One diskful appliance remains a single point of failure. Diskless hosts
  do not add data redundancy. No Veeam protocol/incremental claims follow
  from this storage proof; retain the separate feasibility gate.
