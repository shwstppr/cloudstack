# Veeam–LINSTOR Codex handover (CloudStack 24.0-SNAPSHOT)

For development or investigation, read `veeam-linstor/README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `TASKS.md`, `ACCEPTANCE.md`, and `OPEN_QUESTIONS.md` relative to this directory. For a routine health-only request, use the checks below first; read additional handover documents only if needed to investigate a failure.

Target the CURRENT checked-out Apache CloudStack repository, expected to be 24.0-SNAPSHOT. Confirm with `git status`, `git branch --show-current`, and the root `pom.xml` before proceeding. Do not switch branches, reset files, or overwrite user changes.

Phase 0 is analysis only. Locate real UHAPI, Veeam, KVM and LINSTOR code using repository searches. Do not assume module paths, APIs, or that PR #14194 has merged. Write feasibility findings to `.codex/veeam-linstor/FEASIBILITY.md` and a proposed file-by-file change list; wait for approval before code changes.

Never claim genuine Veeam incremental backup without VBR protocol evidence and a successful incremental restore test. Preserve NFS behavior.

## Routine lab health checks

Use `python3 .codex/veeam-linstor/checks/deployment.py` for the current lab's
read-only health check. For a fresh deployment and cross-host restart, use
`--mode deploy --cycle`; it cleans up only its own new VM. Read
`veeam-linstor/checks/README.md` for options and configuration. Report the
short PASS/FAIL result and read detailed reports/logs only when a check fails;
do not repeat full inventory for a routine health request.
