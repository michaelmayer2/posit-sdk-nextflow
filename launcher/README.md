# posit-workbench-nf-launcher

A thin CLI wrapping `posit.workbench`(`.admin`) with three subcommands shaped like a grid
scheduler's CLI (`sbatch`/`squeue`/`scancel`), so the `nf-workbench` Nextflow plugin (see
`../plugin`) can shell out to it exactly the way Nextflow's built-in Slurm/LSF/SGE executors
shell out to their own scheduler CLIs.

See the repo root README for install instructions and the two supported launch modes
(interactive Positron/RStudio session vs. headless Workbench API).

## Subcommands

- `posit-workbench-nf-launcher submit --cluster NAME --name NAME [--cpus N] [--mem-mb N] [--container IMG] SCRIPT`
  launches `SCRIPT` via `/bin/bash` as a Workbench job and prints its id to stdout.
- `posit-workbench-nf-launcher status`
  prints one `<id> <STATE>` line per job known to Workbench, where `STATE` is one of
  Nextflow's own `AbstractGridExecutor.QueueStatus` names (`PENDING`, `RUNNING`, `HOLD`,
  `DONE`, `ERROR`, `UNKNOWN`) -- computed here so the Groovy side only has to do
  `QueueStatus.valueOf(token)`.
- `posit-workbench-nf-launcher kill ID [ID ...]`
  best-effort job cancellation.
