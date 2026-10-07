# Usage reference

How to use the `nf-workbench` executor in a Nextflow pipeline. It covers every setting the plugin
reads, the command-line params defined by the bundled example, and the `posit-workbench-nf-launcher`
CLI that the plugin calls. See the [root README](../README.md) for installation.

## Plugin vs. example params

Nextflow passes `--someName value` on the `nextflow run` command line through as `params.someName`.
**The plugin reads no `params` itself.** It only reads `nextflow.config` settings
(the `workbench` scope and `process.*`). `--workbenchCluster`, `--workbenchContainer`, and
`--launcherCli` only work because `examples/nextflow.config` copies them into those settings. To
get the same flags in your own pipeline, copy that pattern (see
[Wiring params into config](#wiring-params-into-config)).

## Example pipeline params

These are defined in `examples/nextflow.config`, for `nextflow run examples/main.nf`:

| Param | Default | Sets | Purpose |
|---|---|---|---|
| `--workbenchCluster NAME` | `Kubernetes` | `workbench.cluster` | Workbench cluster that jobs are submitted to. |
| `--workbenchContainer IMG` | `python:3.12-slim` | `process.container` | Default image for processes that don't declare their own `container`. Processes that set `container` keep their own image. |
| `--launcherCli PATH` | `posit-workbench-nf-launcher` | `workbench.launcherCli` | Path or name of the launcher CLI. Use an absolute path if the CLI isn't on `PATH`. |
| `--workbenchResourceProfile NAME` | unset (cluster default) | `workbench.resourceProfile` | Workbench resource profile for every task. See [Resource profiles](#resource-profiles). |

Example:

```bash
cd examples
nextflow run main.nf \
    --workbenchCluster Kubernetes \
    --workbenchContainer python:3.12-slim \
    --launcherCli ~/.local/bin/posit-workbench-nf-launcher \
    --workbenchResourceProfile custom \
    -resume
```

Standard Nextflow options such as `-resume`, `-profile`, `-work-dir`, and `-with-report` work as
usual. The example config defines two empty profiles, `interactive` and `headless`. They exist for
readability only: either way, authentication is chosen from the environment (see
[Credentials](#credentials)).

## Plugin configuration (`nextflow.config`)

### Enabling the plugin and executor

```groovy
plugins {
    id 'nf-workbench@0.1.0'
}

process {
    executor = 'workbench'
}
```

You can also enable the executor for selected processes only, for example
`process { withLabel: 'big' { executor = 'workbench' } }`.

### `workbench` settings

The plugin's own settings go in a top-level `workbench` scope. The plugin declares this scope,
so Nextflow checks the option names: a typo such as `clustr` produces
`WARN: Unrecognized config option 'workbench.clustr'`.

| Setting | Required | Default | Description |
|---|---|---|---|
| `cluster` | **yes** | – | Workbench cluster name. The run stops at startup if this is unset. |
| `launcherCli` | no | `posit-workbench-nf-launcher` | Command used to call the launcher CLI. It is looked up on the `PATH` of the `nextflow` process. |
| `resourceProfile` | no | unset | Default Workbench resource profile for every task. A single process can override it with `ext.resourceProfile`. If neither is set, Workbench uses the cluster's default profile (`custom` on a stock setup). |

To list valid cluster names:

```python
from posit.workbench.admin import Client   # or: from posit.workbench import Client, if in-session
print([c["name"] for c in Client().compute_envs.list()["clusters"]])
```

### Generic grid-executor settings

`nf-workbench` is built on Nextflow's grid-executor base class, so the standard
[executor settings](https://www.nextflow.io/docs/latest/reference/config.html#executor) also
apply. They go under `executor.$workbench` (or plain `executor`), **not** under the `workbench`
scope. The ones you're most likely to change:

| Setting | Effect |
|---|---|
| `queueSize` | Maximum number of Workbench jobs Nextflow keeps submitted at once. |
| `submitRateLimit` | Throttles job submission, e.g. `'10/1min'`. |
| `pollInterval` | How often Nextflow checks tasks for completion (it looks for `.exitcode` in the work dir). |
| `queueStatInterval` | How often `posit-workbench-nf-launcher status` runs. Each call lists **every** job visible to the credential, so on a busy Workbench a longer interval keeps the load down. |
| `exitReadTimeout` | How long to wait for `.exitcode` after Workbench reports a job finished. Raise this if the shared filesystem is slow to sync. |

### Process directives

| Directive | Mapped to | Notes |
|---|---|---|
| `container` | Job `container.image` | **Required on Kubernetes clusters.** Workbench rejects a job without a container (`Kubernetes requires the "container" parameter`). Set `process.container` as a default. |
| `cpus` | `resourceLimits` `cpuCount` | Sent only when greater than 1. With `cpus 1` the cluster's default applies. |
| `memory` | `resourceLimits` `memory` | Converted to MB. |
| `ext.resourceProfile` | Job `resourceProfile` | Overrides `workbench.resourceProfile` for this process. |
| `time`, `disk`, `accelerator`, `queue`, `clusterOptions` | – | Not mapped in this version, and ignored by Workbench. |

Jobs are named `nf-<process name>`, which is how they appear in the Workbench Jobs UI.

### Resource profiles

Workbench admins define resource profiles per cluster, for example in
`launcher.kubernetes.resources.conf`. To list the profiles available on a cluster:

```python
from posit.workbench.admin import Client   # or: from posit.workbench import Client, if in-session
for c in Client().compute_envs.list()["clusters"]:
    print(c["name"], [p["name"] for p in c.get("resourceProfiles") or []])
```

To select a profile for every task, or for individual processes:

```groovy
workbench {
    cluster         = 'Kubernetes'
    resourceProfile = 'small'           // default for every task
}

process {
    withLabel: 'big_mem' {
        ext.resourceProfile = 'large'       // per-process override
    }
}
```

Workbench checks the name when the job is submitted. An unknown profile fails the task with
`Unknown resource profile` (see [Troubleshooting](#troubleshooting)).

`cpus`/`memory` are still sent alongside the profile. With the `custom` profile they set the
job's limits as usual. How Workbench combines them with a non-custom profile's own limits hasn't
been tested yet: if a profile should fully determine a task's resources, leave `cpus`/`memory`
unset for that process.

### Wiring params into config

To expose command-line flags in your own pipeline, copy the pattern from `examples/nextflow.config`:

```groovy
process {
    executor  = 'workbench'
    container = params.workbenchContainer ?: 'python:3.12-slim'
}

workbench {
    cluster     = params.workbenchCluster ?: 'Kubernetes'
    launcherCli = params.launcherCli ?: 'posit-workbench-nf-launcher'
}
```

## Credentials

The launcher CLI handles authentication using the environment it inherits from `nextflow`:

| Where `nextflow run` executes | What to set |
|---|---|
| Terminal inside a Positron / RStudio Pro Workbench session | Nothing. The session's own credentials are picked up automatically. |
| Anywhere else (laptop, CI, a headless "driver" Workbench Job) | `WORKBENCH_SERVER` (Workbench URL) and `WORKBENCH_API_KEY` (API token). |

## Launcher CLI reference

You don't normally call `posit-workbench-nf-launcher` yourself, but it's useful for debugging.
Each subcommand can be run by hand with the same credentials Nextflow would use:

```text
posit-workbench-nf-launcher submit --cluster NAME --name NAME
                                   [--cpus N] [--mem-mb N] [--container IMG]
                                   [--resource-profile NAME] SCRIPT
posit-workbench-nf-launcher status
posit-workbench-nf-launcher kill ID [ID ...]
```

| Subcommand | Output | Notes |
|---|---|---|
| `submit` | The new job id on stdout | Runs `SCRIPT` with `/bin/bash`, as an absolute path on the shared filesystem. Exits 1 with `error: failed to submit job: ...` on stderr if the Workbench API rejects the job. |
| `status` | One `<id> <STATE>` line per job | `STATE` is one of `PENDING`, `RUNNING`, `HOLD`, `DONE`, `ERROR`, `UNKNOWN`. |
| `kill` | Nothing | Best-effort. Errors such as an already-finished job are ignored. |

## Troubleshooting

| Error | Cause / fix |
|---|---|
| `Cannot run program "posit-workbench-nf-launcher" ... No such file or directory` | The CLI isn't on the `PATH` of the `nextflow` process. Activate the launcher venv, install the CLI with `uv tool install ./launcher`, or set `launcherCli` / `--launcherCli` to an absolute path. |
| `Missing required config workbench.cluster` | Set `workbench.cluster`, or pass `--workbenchCluster` with the example config. |
| `Unknown resource profile` | The profile name isn't defined for that cluster. Check the names with the snippet under [Resource profiles](#resource-profiles), and check both `workbench.resourceProfile` and any `ext.resourceProfile`. |
| `Unrecognized config option 'executor.$workbench.cluster'` (or `launcherCli`/`resourceProfile`) | These settings moved to the top-level `workbench` scope. Change `executor { $workbench { cluster = ... } }` to `workbench { cluster = ... }`. |
| `Kubernetes requires the "container" parameter` | A process has no container. Set a default with `process.container` (or `--workbenchContainer` with the example config). |
| Nextflow can't find or download plugin `nf-workbench` | Run `gradle install` in `plugin/`, and check that the version in `plugins { id 'nf-workbench@...' }` matches `plugin/build.gradle`. |
| Task hangs after its job finished in Workbench | `.exitcode` isn't visible from the Nextflow side. Check that the work dir is on a filesystem shared with the Workbench jobs, or raise `exitReadTimeout`. |
