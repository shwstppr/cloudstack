# Blockers Codex must resolve, not assume

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
