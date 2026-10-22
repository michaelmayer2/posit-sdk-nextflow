#!/bin/bash
# A Nextflow driver to run as a Workbench Job, so the pipeline keeps running without a session.
# Submit it with the launcher CLI (see docs/usage.md, "Headless runs"). The tasks it starts
# run as Workbench Jobs too, with the credentials this job was given.
#
# Run from a directory on the shared filesystem: the driver, its tasks' work directories and
# the Nextflow cache must be visible to all jobs.
export PATH="$HOME/.local/bin:$PATH"
export NXF_OPTS="${NXF_OPTS:--Xms512m -Xmx3g}"

# exec: Nextflow becomes the job's main process, so stopping the job with
# `posit-workbench-nf-launcher kill --graceful` sends it SIGTERM and it cancels its tasks.
exec nextflow -log nextflow.log run main.nf -ansi-log false "$@" >>driver.log 2>&1
