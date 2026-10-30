# Ordered backlog

- [x] Map UHAPI endpoint implementations (see FEASIBILITY.md).
- [ ] Capture current VBR traffic and version/build.
- [ ] Identify real VBR disk read protocol; produce sample redacted trace.
- [x] Identify CloudStack LINSTOR integration and storage identifiers.
- [x] Verify Ubuntu controller/storage appliance, AlmaLinux 8 diskless satellites, snapshots and guest deployment.
- [x] Verify HTTPS control API discovery; identify backup-time restriction, omitted LINSTOR domains and raw/cow metadata mismatch.
- [ ] Approve CHANGE_PLAN.md before integration source changes.
- [ ] Decide snapshot ownership, lifecycle, concurrency and locking.
- [ ] Build read-only full snapshot data transport accepted by VBR.
- [ ] Validate full backup and full restore.
- [ ] Identify verified changed-extent source; document persistence semantics.
- [ ] Prove VBR protocol accepts incremental data and metadata.
- [ ] Implement chain generation and fallback handling.
- [ ] Validate two incrementals and point-in-time restore.
- [ ] Add integration tests, logs, metrics, and CCC26 demo notes.
