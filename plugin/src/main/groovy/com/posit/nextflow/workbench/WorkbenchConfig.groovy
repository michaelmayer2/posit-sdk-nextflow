package com.posit.nextflow.workbench

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * The plugin's own top-level {@code workbench} config scope, declared so Nextflow's config
 * validator recognizes these options.
 *
 * They can't live under {@code executor.$workbench}: core Nextflow validates that placeholder
 * scope against its fixed set of generic executor options, and refuses plugin scopes that
 * collide with an existing scope name like {@code executor}. Hence a separate scope, the same
 * way {@code aws.batch}/{@code google.batch}/{@code k8s} are separate from {@code executor}.
 * Generic grid-executor options ({@code queueSize}, {@code pollInterval}, ...) still go in the
 * {@code executor} scope.
 */
@ScopeName('workbench')
@Description('''
    The `workbench` scope configures the `workbench` executor, which runs each task as a Posit Workbench Job.
''')
@CompileStatic
class WorkbenchConfig implements ConfigScope {

    static final String DEFAULT_LAUNCHER_CLI = 'posit-workbench-nf-launcher'

    @ConfigOption
    @Description('''
        Workbench cluster (compute environment) to submit jobs to, e.g. `Kubernetes` (required).
    ''')
    final String cluster

    @ConfigOption
    @Description('''
        Command used to invoke the launcher CLI, resolved on the `PATH` of the `nextflow` process (default: `posit-workbench-nf-launcher`).
    ''')
    final String launcherCli

    @ConfigOption
    @Description('''
        Default Workbench resource profile for every task; a process can override it with `ext.resourceProfile` (default: the cluster's default profile).
    ''')
    final String resourceProfile

    /* required by the extension point mechanism */
    WorkbenchConfig() {}

    WorkbenchConfig(Map opts) {
        cluster = opts.cluster as String
        launcherCli = (opts.launcherCli ?: DEFAULT_LAUNCHER_CLI) as String
        resourceProfile = opts.resourceProfile as String
    }
}
