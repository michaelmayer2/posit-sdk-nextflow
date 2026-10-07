# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Nextflow executor (`process.executor = 'workbench'`) that runs each task as a Posit Workbench Job. It has two independently built pieces that talk to each other only via a subprocess CLI contract:

- `launcher/` — `posit-workbench-nf-launcher`, a Python CLI over `posit.workbench` / `posit.workbench.admin` (from `posit-sdk`, pinned in `pyproject.toml` to the `workbench` branch of `michaelmayer2/posit-sdk-py` via `[tool.uv.sources]`).
- `plugin/` — `nf-workbench`, a Groovy Nextflow plugin. `WorkbenchExecutor` extends `AbstractGridExecutor` and shells out to the CLI the same way the built-in Slurm executor shells out to `sbatch`/`squeue`/`scancel`.

This is a sibling of `posit-sdk-snakemake`. The two share no code, but they are meant to keep the same auth model and job lifecycle. Code comments refer to it when explaining design choices.

## Installation

### Prerequisites 

#### Java SDK

As a prerequisite, NextFlow needs Java 17+ & gradle, you can install this via
```bash
curl -s https://get.sdkman.io | bash
source "~/.sdkman/bin/sdkman-init.sh" 
sdk install java 17.0.10-tem
sdk install gradle
```

#### Nextflow

Install Nextflow as a normal user (no root needed; requires Java 17+, e.g. via SDKMAN: ``):
```bash
curl -s https://get.nextflow.io | bash          # downloads ./nextflow
chmod +x nextflow && mkdir -p ~/.local/bin && mv nextflow ~/.local/bin/   # ensure ~/.local/bin is on PATH
nextflow -version                               # plugin targets >= 25.10.0; pin with NXF_VER=25.10.0 if needed
```

### Launcher plugin 

Launcher (Python >=3.11, uv):
```bash
cd launcher && uv venv && uv pip install ".[dev]"
uv run pytest                                   # all tests
uv run pytest tests/test_cli.py::test_submit_prints_job_id   # single test
uv run ruff check . && uv run ruff format .     # line-length 99, isort rules enabled
```

Plugin (Gradle, `io.nextflow.nextflow-plugin`, targets Nextflow 25.10.0):
```bash
cd plugin
gradle test                                     # Spock tests (or `make test`)
gradle test --tests 'com.posit.nextflow.workbench.WorkbenchExecutorTest'
gradle install                                  # installs into ~/.nextflow/plugins
cp .venv/bin/posit-workbench-nf-launcher ~/.local/bin # copies launcher binary to ~/.local/bin
```
The repo has no Gradle wrapper. Run `gradle wrapper` to generate one, then use `make GRADLE=./gradlew ...`.

Example pipelines (need a real Workbench, the CLI on `PATH`, and the plugin installed). Run them from a directory on the shared filesystem (not `/tmp`), because the Workbench Jobs need to see `work/`:
```bash
cd examples && nextflow run main.nf --workbenchCluster <cluster-name>   # produces sum.txt = 55
cd examples/rnaseq && nextflow run main.nf                              # 10 jobs, up to 8 parallel; see results/pipeline_info/trace.txt
```

## Architecture: the CLI ↔ plugin contract

Changes to either side usually need a matching change on the other side:

- **`submit --cluster C --name N [--cpus N] [--mem-mb N] [--container IMG] [--resource-profile P] SCRIPT`** prints only the job id to stdout. `parseJobId` trims stdout and uses all of it as the id. The plugin passes `scriptFile.getName()`, a relative name, and relies on `AbstractGridExecutor` running submit with the task `workDir` as cwd. The CLI then resolves it with `os.path.abspath` and launches it as `/bin/bash <abs path>`.
- **`status`** prints one `<id> <STATE>` line for every job visible to the credential, with no id filtering. `STATE` must be a Nextflow `QueueStatus` enum name (`PENDING|RUNNING|HOLD|DONE|ERROR|UNKNOWN`). The Workbench→Nextflow status mapping lives only in `cli.py:_queue_status`, and Groovy just calls `QueueStatus.valueOf`. Keep that mapping out of the Groovy code.
- **`kill ID...`** is best-effort and ignores `WorkbenchError`.

Other design points:
- **Resources** go on the submit command line as structured args, not as script header directives, so `getDirectives` is a no-op. Only `cpus` (sent only when >1) and `memory` are mapped, to `resourceLimits` `cpuCount`/`memory`. This matches the Snakemake sibling's coverage. `time`/`disk`/`accelerator` are intentionally not mapped.
- **Resource profiles:** `workbench.resourceProfile` is the default, and per-process `ext.resourceProfile` overrides it (`resourceProfileFor`). The CLI sends the profile as `resourceProfile` on the `launch_job` job spec. posit-sdk's `jobs.launch()` has no parameter for it, so `cmd_submit` builds the spec with `jobs._build_job` and calls `rpc.call` directly. Confirmed live: the Launcher validates the name (`Unknown resource profile`), and jobs without one get `custom`.
- **Header script** exports `HOME`/`USER`/`LOGNAME` from the driving JVM. Workbench containers running as a non-root LDAP uid may have no passwd entry.
- **Auth** happens entirely in the CLI subprocess, using the environment it inherits from Nextflow. `_make_client` tries the in-session `posit.workbench.Client()` first (ambient session cookie) and falls back to `admin.Client()` (`WORKBENCH_SERVER` + `WORKBENCH_API_KEY`) on `OSError`. The tests force the admin path by unsetting `POSIT_PRODUCT`/`RS_SERVER_ADDRESS`, and they mock Workbench's RPC endpoints (`/api/<method>`) with `responses`.
- **A shared filesystem is assumed** between the Nextflow driver and the Workbench jobs. Task staging, `.command.run`, and completion detection through `.exitcode` all go through `task.workDir`.
- **Config:** the plugin's settings live in its own top-level `workbench` scope, declared by `WorkbenchConfig` (a `ConfigScope` extension point registered in `build.gradle`) so Nextflow's config validator knows about them. They can't go under `executor.$workbench`: core Nextflow validates that block against its fixed list of executor options, and ignores plugin scopes whose name collides with an existing scope. Generic grid-executor options (`queueSize`, `pollInterval`, ...) go in the plain `executor` scope. `executor.$workbench.*` works at runtime too, but Nextflow 25.10 warns about every `executor.$<name>.*` option, even for core executors. Run validation checks without `nextflow -q`, which hides the warnings. `workbench.cluster` is required (`register()` aborts without it), and `launcherCli` defaults to `posit-workbench-nf-launcher`. A new setting needs a `@ConfigOption` field in `WorkbenchConfig`. The plugin reads no `params`. The `--workbenchCluster`/`--workbenchContainer`/`--launcherCli` flags exist only because `examples/nextflow.config` maps them into config.
- **User-facing docs:** `docs/usage.md` is the usage reference (config settings, directive mapping, example params, CLI, troubleshooting). Update it whenever you add or change a config setting, directive mapping, example param, or CLI flag.

Known gaps, still unverified end-to-end: no wall-time/disk mapping, log retrieval only works over the shared filesystem, and env-var inheritance across the Positron/RStudio terminal → Nextflow → CLI chain hasn't been confirmed.
