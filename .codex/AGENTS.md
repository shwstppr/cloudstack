# Veeam–LINSTOR Codex handover (CloudStack 24.0-SNAPSHOT)

Read `veeam-linstor/README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `TASKS.md`, `ACCEPTANCE.md`, and `OPEN_QUESTIONS.md` relative to this directory.

Target the CURRENT checked-out Apache CloudStack repository, expected to be 24.0-SNAPSHOT. Confirm with `git status`, `git branch --show-current`, and the root `pom.xml` before proceeding. Do not switch branches, reset files, or overwrite user changes.

Phase 0 is analysis only. Locate real UHAPI, Veeam, KVM and LINSTOR code using repository searches. Do not assume module paths, APIs, or that PR #14194 has merged. Write feasibility findings to `.codex/veeam-linstor/FEASIBILITY.md` and a proposed file-by-file change list; wait for approval before code changes.

Never claim genuine Veeam incremental backup without VBR protocol evidence and a successful incremental restore test. Preserve NFS behavior.
