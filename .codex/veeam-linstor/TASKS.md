# Ordered backlog

- [x] Map UHAPI endpoint implementations (see FEASIBILITY.md).
- [x] Capture current VBR traffic and version/build; see VBR_VALIDATION.md.
- [x] Identify real VBR NBD/image-server full/incremental read and raw restore protocol; private redacted traces recorded.
- [x] Identify CloudStack LINSTOR integration and storage identifiers.
- [x] Verify Ubuntu controller/storage appliance, AlmaLinux 8 diskless satellites, snapshots and guest deployment.
- [x] Verify HTTPS control API discovery; identify backup-time restriction, omitted LINSTOR domains and raw/cow metadata mismatch.
- [x] Approve CHANGE_PLAN.md before integration source changes (user approved 2026-10-30).
- [x] Implement local feature-gated LINSTOR domain discovery and raw disk metadata; 335 module tests passed before transport changes. Not deployed.
- [x] Decide and implement snapshot ownership, lifecycle, concurrency and locking; see IMPLEMENTATION_STATUS.md.
- [x] Implement single-disk native snapshot/raw read-only export and new-target raw restore, default-off; user-installed packages passed live VBR full backup and restore, with boot confirmed.
- [x] Generalize shared backup/agent/discovery code around provider capabilities; move LINSTOR flags and identifier validation into the LINSTOR plugin (user requested 2026-10-31).
- [x] Build read-only full snapshot data transport accepted by VBR.
- [x] Validate a VBR full backup and independent restore to a new VM; user confirmed the restored VM boots. Selected-file hash comparison remains unrecorded.
- [x] Identify candidate changed-extent sources and persistence limits; see FEASIBILITY.md.
- [ ] Prove VBR protocol accepts incremental data and metadata.
- [ ] Implement chain generation and fallback handling.
- [ ] Validate two incrementals and point-in-time restore.
- [ ] Add integration tests, logs, metrics, and CCC26 demo notes.
