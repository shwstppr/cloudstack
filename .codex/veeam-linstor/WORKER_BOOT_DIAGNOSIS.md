# Veeam worker gamma: initialization mismatch

Observed 2026-10-30, worker `gamma`, CloudStack VM ID 9,
UUID `2a324020-3f0b-41ff-8993-de26773dbd35`, instance `i-4-9-VM`, host1.

## Evidence

- VBR starts the VM successfully. Live console passed GRUB and reached Linux
  login; QEMU guest agent responded. Secure Boot did not prevent this boot.
- Guest reports Rocky Linux from CIQ LTS 9.6, kernel
  `5.14.0-570.60.1+2.2.el9_6_ciq.x86_64`, hostname `rocky-stig`.
- Guest agent reports eth0 MAC `02:01:00:ce:00:03` with no IP address.
  CloudStack allocated `172.120.0.56`; router logs show no DHCP request.
- The router has a 11952-byte user-data file for that address. CloudStack stores
  a valid cloud-config payload including hostname, files, runcmd and shutdown.
  Do not publish the payload: it contains worker private-key material.
- The live libvirt XML has no populated configuration-drive CD-ROM.
- Read-only inspection of a preserved disk copy found:

  ```yaml
  # /etc/cloud/cloud.cfg.d/90_dpkg.cfg
  datasource_list: [ ConfigDrive, OpenStack, OpenNebula, NoCloud ]
  # /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg
  network: {config: disabled}
  ```

- `/etc/NetworkManager/system-connections` is empty. Boot messages show eth0
  carrier up, then disconnected. `/var/lib/cloud` has no instance state and
  `/var/log` has no cloud-init log. Cloud-init service links exist under
  `cloud-init.target.wants`; `/etc/cloud/cloud-init.disabled` is absent.

## Finding and next step

The image's initialization contract does not match the network's VirtualRouter
UserData delivery. It excludes the CloudStack datasource and disables network
rendering, while CloudStack provides no ConfigDrive. Consequently the supplied
worker setup and shutdown commands are not executed in the observed attempt.

Align delivery with an accepted local datasource (ConfigDrive/NoCloud), including
network configuration, or adapt a diagnostic image to enable CloudStack
discovery and initial DHCP. Inspect CloudStack's existing ConfigDrive support
before choosing an implementation; avoid changing the shared network offering
or modifying the vendor image without a concrete change plan.

No guest configuration, disk contents, network offering or service configuration
was changed during diagnosis. Diagnostic copies are not restore backups: the VM
was running when captured. A qemu-img conversion encountered a read error; a
separate full file copy was preserved and successfully inspected read-only.
Source qcow2 structural check reported no errors. The read error is not claimed
as the initialization root cause.

Local private diagnostics: `/tmp/gamma-original.qcow2`,
`/tmp/gamma-domain.xml`, `/tmp/gamma-console*.png`,
`/tmp/gamma-cloud-inspection.txt`, `/tmp/gamma-messages-inspection.txt`.
Keep these private; the disk and logs may contain sensitive data.

## tata: worker service connection loss after successful initialization

Worker VM ID 11, UUID `73cc18e0-0de0-4992-b0cf-808e48dc5148`, initialized
successfully via ConfigDrive. It shut down after initialization and VBR started
it again on host2. Guest agent reported hostname `tata` and IP `172.120.0.56`.

Fetched Windows `Veeam.PlatformSvc.log`, `Svc.VeeamBackup.log`, and the downloaded
tata worker ZIP into private directory `/tmp/veeam-tata-investigation`.
Platform-service times are Windows local time (UTC minus seven hours):

- 10:48:57: worker-service connection established.
- 11:00:22: connection to `172.120.0.56:19000` lost, remote connection reset.
- 11:02:14: snapshot polling timed out, then worker reconnect began.
- 11:06:55: reconnect failed with socket timeout 10060.
- 11:06:57: VBR cleanup completed stopping the VM.

The Windows-collected worker ZIP ends before connection loss. Additional
read-only inspection of the stopped worker disk over SSH found its listener
started on TCP 19000 and accepted VBR connections. Guest system logs continue
after the snapshot error; the VM was not left permanently CPU-paused. Snapshot
request had quiescevm=false. No service crash was established from these logs.

Confirmed network allocation conflicts:

- `veeam-svc` allocates `172.120.0.56-172.120.0.60`, gateway `.1`, mask /24.
- tata's NIC MAC is `02:01:00:ce:00:04`, IP `.56`.
- With tata stopped, both VBR and the management server resolve `.56` to
  `1e:00:45:00:00:fb`; an Apache HTTPS server responds there. Another machine
  therefore occupies the worker IP. Its identity has not been established.
- This network's router `r-10-VM` occupies `.57`, MAC `1e:01:a8:00:00:fc`.
  User identifies `.57` as host2 of another MBX environment,
  `qa3-observe-422-f5a3ae71-kvm` (mgmt `.237`, host1 `.230`).
- Direct and management-mediated SSH to `.237` returned No route to host;
  ownership of `.56` could not be checked in that environment.

The worker connection problem has a concrete IP-allocation conflict independent
of the snapshot null-pointer bug. Reserve a non-overlapping range in the outer
MBX network, update/recreate the worker shared network using that allocation,
and let VBR redeploy/reinitialize the worker. Do not pick addresses based solely
on failed pings or manually change only the worker IP, since its network router
also conflicts. No network or VM changes were made during this investigation.
