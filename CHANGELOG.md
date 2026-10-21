# Changelog

All notable changes to `nf-workbench` (the Nextflow plugin) and `posit-workbench-nf-launcher`
(the launcher CLI) are documented here. The two are versioned together.

## 0.2.0 - 2026-10-21

### Added

- Run events: with `workbench.eventsFile` set, the plugin appends workflow and task events to that
  file as JSON lines (`started`, `task_submitted`, `task_started`, `task_completed`,
  `task_cached`, `error`, `completed`). Workflow events include Nextflow's `errorReport`. This
  works for any executor when the plugin is loaded, e.g. with `-plugins nf-workbench@0.2.0`. It is
  used by the Positron Nextflow extension to show live run status. See
  [Run events](docs/usage.md#run-events).
- Launcher: `clusters [--json]` lists cluster names and their resource profiles.
- Launcher: `whoami [--json]` shows the active credential path (`session` or `api-key`), the
  user and the server.
- Launcher: `status` accepts job ids to filter by, and `--json` for structured output.

### Changed

- The plugin and launcher versions are now 0.2.0. Update `plugins { id 'nf-workbench@0.2.0' }`
  in pipeline configs.

The plugin's `submit`/`status`/`kill` contract with the launcher is unchanged.

## 0.1.0 - 2026-10-07

### Added

- `nf-workbench` executor (`process.executor = 'workbench'`), which runs each task as a Posit
  Workbench Job through the `posit-workbench-nf-launcher` CLI (`submit`, `status`, `kill`).
- `cpus` and `memory` mapped to Workbench resource limits, and `container` to the job image.
- Resource profiles: `workbench.resourceProfile`, overridden per process with
  `ext.resourceProfile`.
- The top-level `workbench` config scope (`cluster`, `launcherCli`, `resourceProfile`), declared
  so that Nextflow's config validator recognises it.
- Examples: a three-step pipeline and an RNA-seq pipeline with parallel Workbench Jobs.
