# CloudStack + Veeam + LINSTOR — Codex handover

Goal: extend the existing CloudStack oVirt-compatible UHAPI integration to support full and *genuine* incremental backup of KVM VM disks backed by LINSTOR. Target: CCC26 proof of concept. No claims of Veeam compatibility until observed in a real VBR session.

Current local implementation and validation: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).
Actual VBR NFS full/incremental/restore evidence: [VBR_VALIDATION.md](VBR_VALIDATION.md).
LINSTOR full/raw restore is implemented locally behind a default-off flag;
live LINSTOR VBR acceptance and LINSTOR incremental design remain pending.

## Existing environment (verify in repository and lab)
- CloudStack KVM with embedded Jetty UHAPI at `/ovirt-engine/api` on port 8090 (HTTPS configured and exercised by VBR).
- Bearer authentication via `POST /sso/oauth/token`; configured Basic access also verified.
- UHAPI endpoints for VMs, disks, diskattachments, imagetransfers, etc.; image transfer upload on host port 54322.
- Existing CloudStack + Veeam lab, Ubuntu LINSTOR controller/storage appliance with AlmaLinux 8 diskless KVM satellites. Provisioning/deployment checks already passed; use reusable health checks.
- Backup proxy VM on shared network; verify management-host reachability. Never assume a guest can reach a host through VR.
- Relevant CloudStack PR: https://github.com/apache/cloudstack/pull/14194 (inspect implementation; do not assume its merged state or applicability).

## Required sequence
1. Inventory repo, actual branch, Veeam protocol calls/logs, storage architecture, LINSTOR versions and topology.
2. Write findings and protocol feasibility gate before implementation.
3. Implement full backup with recoverable restore, then real incremental with tested chain.
4. Keep NFS paths intact. Feature-gate experimental functionality.

Start with `CODEX_PROMPT.md`, then `ACCEPTANCE.md`.
