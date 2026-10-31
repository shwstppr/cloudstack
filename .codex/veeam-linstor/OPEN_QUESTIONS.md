# Blockers Codex must resolve, not assume

Status 2026-10-31: repository/topology details are recorded in FEASIBILITY.md
and APPLIANCE_SETUP.md. VBR 13.1.0.308 with oVirt plugin 13.8.0.281 was
observed performing NFS full/incremental backup and independent restore;
see VBR_VALIDATION.md. Questions 1, 2, 4, 5, 6 and the NFS portion of 9 have
baseline evidence. Full LINSTOR transport is implemented locally (see
IMPLEMENTATION_STATUS.md); live LINSTOR acceptance remains open. Questions
3, 7, 8 and LINSTOR incremental portions of 9 remain separate design gates.
The approved first transport supports one crash-consistent disk; it does
not establish multi-disk/application consistency or incremental baselines.

1. What exact CloudStack branch and UHAPI code are present? Which VBR version/build?
2. Which Veeam oVirt endpoints and image transfer modes are invoked for backup and restore?
3. Does Veeam's oVirt workflow support a custom incremental source, or only vendor-specific CBT semantics?
4. What LINSTOR version, DRBD version, backing storage driver and snapshot features are installed?
5. Is the 100GB AlmaLinux VM intended as controller only? Where do satellites and actual replicated storage live?
6. Can a snapshot be safely exposed for read without attaching it to the running guest?
7. Which component guarantees snapshot/bitmap atomicity with VM writes?
8. How are multi-disk consistency, crashes and baseline invalidation handled?
9. What actual test evidence establishes that VBR accepted an incremental restore point?
10. How will the backup be restored if the original LINSTOR volume and CloudStack VM are deleted?
