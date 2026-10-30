# Codex execution prompt

You are working on Apache CloudStack KVM/Veeam integration for CCC26. Read all files in this handover. Do not immediately write implementation code.

Phase 0 — inventory and evidence:
- Locate existing embedded Jetty UHAPI routes, authentication, disk metadata, image-transfer handlers, Veeam discovery and transport code, CloudStack backup provider interfaces, KVM storage abstractions, and LINSTOR integration.
- Examine PR https://github.com/apache/cloudstack/pull/14194 and determine what is reusable, merged, or experimental. Record commit/branch identifiers.
- Trace actual Veeam requests during a successful existing NFS full backup, if available. Document HTTP request/response shapes and VBR version. Do not infer that oVirt API parity guarantees support for arbitrary storage or incrementals.
- Identify whether Veeam initiates read transfers, whether its proxy expects specific oVirt image-transfer semantics, and whether existing upload code can serve backups. Validate with traces.
- Investigate LINSTOR version-specific snapshot capabilities, backing technology (DRBD/LVM-thin/ZFS/etc.), stable read access, and available changed-block metadata. Do not equate LINSTOR snapshots with CBT.
- Write `.codex/veeam-linstor/FEASIBILITY.md` with protocol, storage, data-plane, and restore findings; mark unknowns.

Phase 1 — implement a minimal full-backup vertical slice:
- Discover LINSTOR-backed volumes using CloudStack's canonical storage identity, not string matching.
- Design per-volume backup session: create point-in-time snapshot, expose a stable read-only snapshot to the transport, stream bytes using the Veeam-supported transfer protocol, finalize, and cleanup.
- Use timeouts, cancellation, bounded buffers, credentials isolation, and idempotent cleanup. Keep active source volumes untouched.
- Implement full restore into a newly allocated LINSTOR-backed volume, validate checksum and bootability.
- Log lifecycle with session/VM/volume/snapshot IDs, without leaking tokens.

Phase 2 — genuine incrementals (only after feasibility gate):
- Determine whether a trustworthy changed-extent source exists for the deployed backing store and Veeam protocol. Candidates must be verified: native backing-store tracking, persistent dirty bitmaps at QEMU layer, or an independently implemented snapshot-diff mechanism. Do not invent LINSTOR CBT APIs.
- Persist baseline identity, generation/epoch, snapshot IDs, extent map and chain metadata. Ensure tracking survives restart or force a full backup.
- Capture consistent extents for each interval, coalesce/alignment-check ranges, and send only changed extents if the observed Veeam protocol supports that mode.
- If Veeam cannot accept externally supplied incremental extents via the implemented oVirt-compatible interface, document the blocker and do NOT misrepresent an internal differential export as a Veeam incremental. Implement a clearly separate experimental path only if useful.
- Support fallback to full on missing bitmap, resized disk, changed storage identity, invalid baseline, chain gap, or tracking reset.

Phase 3 — validation:
- Automated unit and integration tests; real VBR interoperability and restore drills; failure injection.
- Record transferred bytes, changed bytes, elapsed time and final restored content hashes.
- Preserve existing NFS behavior and avoid unreviewed CloudStack API changes.

Work in small commits. Before each phase, provide file-level plan and tests. No destructive lab operations without confirmation. Report blockers immediately with traces and alternatives.
