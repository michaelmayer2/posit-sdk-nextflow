package com.posit.nextflow.workbench

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException
import nextflow.executor.AbstractGridExecutor
import nextflow.processor.TaskRun
import nextflow.util.Escape
import nextflow.util.ServiceName

/**
 * Executor that runs each Nextflow task as a Posit Workbench Job, by shelling out to the
 * {@code posit-workbench-nf-launcher} CLI (a thin wrapper around the Python {@code posit-sdk})
 * exactly the way Nextflow's built-in Slurm/LSF/SGE executors shell out to
 * {@code sbatch}/{@code bsub}/{@code qsub}.
 *
 * Users select this executor with {@code process.executor = 'workbench'} and must set the
 * target cluster in the plugin's own {@code workbench} scope (see {@link WorkbenchConfig}):
 * <pre>
 * workbench {
 *     cluster = 'my-cluster'                        // required
 *     launcherCli = 'posit-workbench-nf-launcher'    // optional, defaults to this
 *     resourceProfile = 'small'                      // optional, cluster's default if unset
 * }
 * </pre>
 * A single process can override the resource profile with {@code ext.resourceProfile = 'large'}.
 *
 * Assumes a shared filesystem between wherever Nextflow is driven from and wherever Workbench
 * runs jobs (the same assumption the sibling posit-sdk-snakemake integration makes), since task
 * staging, the generated {@code .command.run} wrapper script, and completion detection (via the
 * {@code .exitcode} file -- see {@code GridTaskHandler}) all go through {@code task.workDir}.
 *
 * Authentication is handled entirely inside the CLI subprocess, which inherits whatever
 * environment this JVM process was started with: an ambient Workbench session (interactive use
 * from a Positron/RStudio Pro session) is picked up automatically, otherwise
 * {@code WORKBENCH_SERVER}/{@code WORKBENCH_API_KEY} must be set (headless use, e.g. when the
 * whole {@code nextflow run} invocation is itself submitted as a Workbench Job via the
 * Workbench API).
 */
@Slf4j
@CompileStatic
@ServiceName('workbench')
class WorkbenchExecutor extends AbstractGridExecutor {

    @PackageScope String cluster
    @PackageScope String launcherCli
    @PackageScope String resourceProfile

    @Override
    void register() {
        super.register()
        final opts = new WorkbenchConfig((session.config.get('workbench') ?: [:]) as Map)
        cluster = opts.cluster
        launcherCli = opts.launcherCli
        resourceProfile = opts.resourceProfile
        if( !cluster )
            throw new AbortOperationException(
                "Missing required config `workbench.cluster` -- set it to a valid " +
                "Workbench compute env/cluster name, e.g. one of " +
                "`Client().compute_envs.list()['clusters'][*]['name']`"
            )
        log.debug "[WORKBENCH] cluster=$cluster launcherCli=$launcherCli resourceProfile=$resourceProfile"
    }

    /**
     * Resources are passed directly on the submit command line (see {@link #getSubmitCommandLine}),
     * not via embedded job-script header directives -- the Workbench Jobs API takes structured
     * fields, not a scheduler-parsed script header. Nothing to add here.
     */
    @Override
    protected List<String> getDirectives(TaskRun task, List<String> initial) {
        return initial
    }

    @Override
    protected String getHeaderToken() { '#' }

    @Override
    protected String getHeaderScript(TaskRun task) {
        final base = super.getHeaderScript(task)
        // Workbench job containers running as a non-root uid can lack $HOME/$USER/$LOGNAME
        // entirely (no /etc/passwd entry for the LDAP-resolved uid) -- confirmed in the sibling
        // Snakemake integration (posit-sdk-snakemake) to break Python's cache-dir resolution and
        // similar user-lookup calls inside the task container. Exporting the driving process's
        // own values is safe since task.workDir is a shared mount either way.
        final home = System.getProperty('user.home')
        final user = System.getProperty('user.name')
        return "export HOME=${Escape.path(home)} USER=${user} LOGNAME=${user}\n" + base
    }

    @Override
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile) {
        final cmd = [launcherCli, 'submit', '--cluster', cluster, '--name', getJobNameFor(task)]

        final cpus = task.config.getCpus()
        if( cpus > 1 )
            cmd << '--cpus' << cpus.toString()

        final mem = task.config.getMemory()
        if( mem )
            cmd << '--mem-mb' << mem.toMega().toString()

        final container = task.getContainer()
        if( container )
            cmd << '--container' << container

        final profile = resourceProfileFor(task)
        if( profile )
            cmd << '--resource-profile' << profile

        cmd << scriptFile.getName()
        return cmd
    }

    /**
     * Per-process {@code ext.resourceProfile} wins over the executor-wide
     * {@code workbench.resourceProfile}; neither set means the cluster's default profile.
     */
    @PackageScope String resourceProfileFor(TaskRun task) {
        final ext = task.config.get('ext') as Map
        return (ext?.get('resourceProfile') ?: resourceProfile) as String
    }

    @Override
    def parseJobId(String text) {
        final id = text.trim()
        if( !id )
            throw new IllegalStateException("Invalid Workbench submit response: empty job id")
        return id
    }

    @Override
    protected List<String> getKillCommand() {
        return [launcherCli, 'kill']
    }

    @Override
    protected List<String> queueStatusCommand(Object queue) {
        // No `queue`/partition concept in the Workbench Jobs API -- one status command lists
        // every job known to Workbench for the current credential, like `squeue` without `-p`.
        return [launcherCli, 'status']
    }

    @Override
    protected Map<String, QueueStatus> parseQueueStatus(String text) {
        final result = new LinkedHashMap<String, QueueStatus>()
        text.eachLine { String line ->
            final trimmed = line.trim()
            if( !trimmed )
                return
            final cols = trimmed.split(/\s+/)
            if( cols.size() != 2 ) {
                log.debug "[WORKBENCH] invalid status line: `$line`"
                return
            }
            try {
                result.put(cols[0], QueueStatus.valueOf(cols[1]))
            }
            catch( IllegalArgumentException e ) {
                log.debug "[WORKBENCH] unknown status token in line: `$line`"
            }
        }
        return result
    }

}
