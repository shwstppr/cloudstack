# Design boundaries and protocol feasibility

Control plane: Veeam VBR -> oVirt-compatible UHAPI embedded in CloudStack -> CloudStack KVM/storage integration -> LINSTOR controller/agents.

Data plane: MUST be determined by observing Veeam's actual disk-download/image-transfer workflow. Existing image upload endpoint is not evidence of a functioning download/backup path. Proposed read-only snapshot transport is a design target, not a verified Veeam-supported mechanism.

Storage: LINSTOR orchestrates DRBD resources and underlying backing stores. Snapshot and changed-block mechanisms depend on actual versions, resource layout and underlying technology. Test snapshot support, snapshot accessibility, quiescing, thin-provisioning behavior, and cleanup on this deployment.

Consistency: for multiple disks, define crash-consistent group boundaries or guest-quiesced approach; verify qemu-guest-agent/VSS integration before advertising application consistency. Start with single disk.

Incremental: distinction between (1) snapshot differential at storage layer, (2) tracking changed blocks and reading them, (3) Veeam accepting incremental data over its integration protocol. ALL THREE must work. The oVirt-compatible API may not expose a supported way to submit custom CBT extents. A hard feasibility gate precedes incremental coding.

Restore: maintain an independent, versioned manifest of snapshot lineage and data integrity if required by transport, but do not pretend a custom manifest is a Veeam backup repository format. Veeam-owned restore path must be demonstrated end-to-end.

Security: HTTPS in deployed tests; scoped short-lived credentials, restricted data-plane access, no log leakage, cleanup of temporary resources, rate limits.

Non-goals: production-grade HA, cross-site disaster recovery, multi-tenant self-service UI, synthetic full, dedupe, immutable backups, encryption-at-rest implementation.
