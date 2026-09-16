"""CLI that submits/polls/cancels Posit Workbench Jobs on behalf of the nf-workbench Nextflow
executor plugin (see ../../plugin). Mirrors the auth and job-lifecycle patterns used by the
sibling Snakemake integration (posit-sdk-snakemake) so both stay consistent, without sharing
code (kept independent by design -- see that repo if these two ever need to be unified).

Subcommands are shaped to slot directly into Nextflow's AbstractGridExecutor grid-executor
contract, the same shape as `sbatch`/`squeue`/`scancel`:

- `submit`: prints the new job's id to stdout, mirroring `sbatch`.
- `status`: prints one `<id> <STATE>` line per known job (no id filtering), mirroring `squeue`
  listing every job for the current user.
- `kill <id> [<id> ...]`: best-effort; mirrors `scancel`.

`STATE` values are Nextflow's own `AbstractGridExecutor.QueueStatus` enum names (PENDING,
RUNNING, HOLD, DONE, ERROR, UNKNOWN), computed here so the Groovy side only has to do
`QueueStatus.valueOf(token)` -- the Workbench-status-to-Nextflow-status mapping lives in exactly
one place (this file), not duplicated in Groovy.
"""

from __future__ import annotations

import argparse
import os
import sys

import posit.workbench
import posit.workbench.admin
from posit.workbench import WorkbenchError

_TERMINAL_FAILURE_STATUSES = frozenset({"Failed", "Killed", "Canceled"})


def _make_client():
    """Prefer the ambient in-session client; fall back to the Bearer-token admin client."""
    try:
        return posit.workbench.Client()
    except OSError:
        return posit.workbench.admin.Client()


def _resource_limits(cpus: int | None, mem_mb: int | None) -> list[dict[str, str]] | None:
    limits = []
    if cpus:
        limits.append({"type": "cpuCount", "value": str(cpus)})
    if mem_mb:
        limits.append({"type": "memory", "value": str(mem_mb)})
    return limits or None


def cmd_submit(args: argparse.Namespace) -> int:
    client = _make_client()
    try:
        job = client.jobs.launch(
            cluster=args.cluster,
            name=args.name,
            exe="/bin/bash",
            args=[os.path.abspath(args.script)],
            resource_limits=_resource_limits(args.cpus, args.mem_mb),
            container={"image": args.container} if args.container else None,
        )
    except WorkbenchError as e:
        print(f"error: failed to submit job: {e}", file=sys.stderr)
        return 1
    print(job["id"])
    return 0


def _queue_status(job: dict) -> str:
    status = job.get("status")
    if status == "Finished":
        return "DONE" if job.get("exitCode") in (0, None) else "ERROR"
    if status in _TERMINAL_FAILURE_STATUSES:
        return "ERROR"
    if status == "Running":
        return "RUNNING"
    if status == "Suspended":
        return "HOLD"
    if status == "Pending":
        return "PENDING"
    return "UNKNOWN"


def cmd_status(args: argparse.Namespace) -> int:
    client = _make_client()
    for job_id, job in client.jobs.get_status_map().items():
        print(f"{job_id} {_queue_status(job)}")
    return 0


def cmd_kill(args: argparse.Namespace) -> int:
    client = _make_client()
    for job_id in args.ids:
        try:
            client.jobs.stop(job_id, force_quit=True)
        except WorkbenchError:
            pass  # best-effort cancellation, e.g. the job may have already finished
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="posit-workbench-nf-launcher")
    sub = parser.add_subparsers(dest="command", required=True)

    submit = sub.add_parser("submit", help="Launch a Workbench job running SCRIPT via /bin/bash")
    submit.add_argument("--cluster", required=True, help="Workbench compute env/cluster name")
    submit.add_argument("--name", required=True, help="Job display name")
    submit.add_argument("--cpus", type=int, default=None)
    submit.add_argument("--mem-mb", type=int, default=None)
    submit.add_argument("--container", default=None, help="Container image reference")
    submit.add_argument("script", help="Path to the script to run")
    submit.set_defaults(func=cmd_submit)

    status = sub.add_parser("status", help="Print `<id> <STATE>` for every known job")
    status.set_defaults(func=cmd_status)

    kill = sub.add_parser("kill", help="Stop one or more jobs by id (best-effort)")
    kill.add_argument("ids", nargs="+")
    kill.set_defaults(func=cmd_kill)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
