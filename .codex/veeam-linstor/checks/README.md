# Reusable lab checks

Keep environment verification scripts in this directory. Start here for
routine health checks instead of repeating inventory, log searches and
manual API calls. Scripts print a short PASS/FAIL summary; inspect the
reported JSON file only when details are needed.

## Prerequisites

Run from a machine with Python 3.6+ and OpenSSH, configured SSH key access
to the management server, KVM hosts and LINSTOR appliance. No pip packages,
passwords or API tokens are required in this directory. The script reaches
CloudStack's existing loopback-only integration API on port 8096 through
SSH; it does not enable or expose that API on the network. The appliance's
LINSTOR CLI must already have working authentication.

`qa6.json` contains the existing lab addresses and UUIDs. Copy that config
for a redeployed lab and update the identifiers; do not bake new addresses
into the Python code. Service offering, template, network and the retained
validation VM must already exist. The default config points to the objects
listed in `../VM_DEPLOYMENT_VALIDATION.md`.

## Commands (from repository root)

Quick, read-only check of the existing validation VM:

```sh
python3 .codex/veeam-linstor/checks/deployment.py
```

Prove a **fresh deployment**, then stop/start that new VM on the other host:

```sh
python3 .codex/veeam-linstor/checks/deployment.py --mode deploy --cycle
```

For a fresh deployment without the cross-host cycle, omit `--cycle`.
Add `--keep-vm` to retain the newly created VM for inspection. These flags
only apply to deployment mode; the script never stops or deletes the
existing `linstor-validation` VM.

Use `--config /path/to/lab.json` for another configuration and `--timeout 360`
for a slower guest. Timeout is per async job or guest-readiness phase,
plus bounded SSH/API request time. No operation is automatically resubmitted
after a timeout or lost mutation response.

## What PASS proves

- Configured CloudStack LINSTOR pool and both KVM hosts are Up.
- Expected LINSTOR nodes are Online.
- VM is Running, with exactly one Ready root volume on the configured
  pool UUID (not just a matching pool name).
- KVM agent and LINSTOR satellite are active on the VM's host; libvirt is
  running the domain with the exact expected DRBD root device.
- Guest answers ping **and returns an SSH protocol banner**, probed from
  its virtual router through the host's existing CloudStack router key.
  Guest login credentials are not required. Router location/control IP
  and guest IP are discovered from the API, not hardcoded.
- In deployment mode: a unique new VM completed deployment using an offering
  constrained by `linstor-poc`. With `--cycle`, its root volume identity is
  unchanged after booting on the other host and guest readiness passes again.
- Unless retained, the script-owned fresh VM is destroyed and expunged.
  Shared network/offering/template and the retained validation VM are untouched.

This does not prove guest application consistency, live migration, reboot
recovery, snapshot integrity or Veeam support; add separate scripts for
those checks. The existing legacy CentOS template is just a lab boot probe.

## Results and cleanup

Exit 0 means all requested checks and cleanup passed; exit 1 means failure;
invalid CLI arguments exit 2. Each run saves IDs, completed jobs, check
details and failure messages to:

```text
/tmp/cloudstack-lab-checks/<UTC timestamp>-<unique suffix>/report.json
```

Override with `--results-dir`. No credentials or full API response dumps
are saved. Configuration and infrastructure are not modified in health mode.

Deployment mode uses the name `linstor-check-<run-id>`. Cleanup validates
the exact UUID, generated name and account before deletion. If an async
mutation is still pending, or its response was lost, cleanup deliberately
retains the VM and reports the job ID/name for investigation. Resolve that
job before deleting anything; rerunning starts a new independent run and
does not guess which previous VM to delete.

Safety/negative-path unit checks (no lab access required):

```sh
python3 .codex/veeam-linstor/checks/test_deployment.py
```

Validated against qa6 on 2026-10-30: read-only health PASS; fresh deployment
plus cross-host cycle and expunge PASS; all five safety tests PASS. The
retained `linstor-validation` VM was not cycled or destroyed by these runs.

## Veeam control request summary

After a VBR UI-triggered operation, collect the control request sequence:

```sh
python3 .codex/veeam-linstor/checks/veeam_trace.py --since 2026-10-30T14:00:00Z
```

Use the operation's actual UTC start time. This is read-only: it inspects
the last 20,000 lines of the current management log and saves only time,
method, sanitized path and status. Query strings, headers and bodies are
not saved; resource UUIDs are replaced with consistent aliases. It neither
enables logging nor starts a VBR job. Increase `--lines` (maximum 100,000)
if needed; rotated logs are not included.

Exit 0 means metadata was found; exit 2 means no evidence in the selected
tail/window; exit 1 is a collection failure. This is **not** a full protocol
body trace or proof of a successful backup/restore. Correlate it with VBR
job/session results, and obtain selected redacted request bodies separately
where needed to establish the contract. Do not enable broad credential/header
logging just to collect this summary.

Sanitizer regression checks:

```sh
python3 .codex/veeam-linstor/checks/test_veeam_trace.py
```
