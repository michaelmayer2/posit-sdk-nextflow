package com.posit.nextflow.workbench

import nextflow.Session
import nextflow.exception.AbortOperationException
import nextflow.executor.AbstractGridExecutor
import nextflow.executor.ExecutorConfig
import nextflow.processor.TaskConfig
import nextflow.processor.TaskRun
import java.nio.file.Paths

import spock.lang.Specification

class WorkbenchExecutorTest extends Specification {

    private WorkbenchExecutor newExecutor(Map workbenchOpts, WorkbenchExecutor executor = new WorkbenchExecutor()) {
        final session = Mock(Session)
        session.getConfig() >> [workbench: workbenchOpts]
        executor.name = 'workbench'
        executor.session = session
        executor.config = new ExecutorConfig([:])
        return executor
    }

    def 'should require a cluster to be configured'() {
        given:
        final executor = newExecutor([:])

        when:
        executor.register()

        then:
        thrown(AbortOperationException)
    }

    def 'should read cluster and launcherCli from the workbench config scope'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', launcherCli: '/opt/bin/my-launcher'])

        when:
        executor.register()

        then:
        noExceptionThrown()
        executor.cluster == 'my-cluster'
        executor.launcherCli == '/opt/bin/my-launcher'
    }

    def 'should default launcherCli when not configured'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])

        when:
        executor.register()

        then:
        executor.launcherCli == 'posit-workbench-nf-launcher'
    }

    def 'should build the kill command with no ids appended'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', launcherCli: 'wb-launch'])
        executor.register()

        expect:
        executor.getKillCommand() == ['wb-launch', 'kill']
    }

    def 'should build a single queue status command with no queue filtering'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', launcherCli: 'wb-launch'])
        executor.register()

        expect:
        executor.queueStatusCommand(null) == ['wb-launch', 'status']
        executor.queueStatusCommand('some-queue') == ['wb-launch', 'status']
    }

    def 'should parse a job id from the submit output'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        expect:
        executor.parseJobId('42\n') == '42'
    }

    def 'should reject an empty submit response'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        when:
        executor.parseJobId('   \n')

        then:
        thrown(IllegalStateException)
    }

    def 'should map CLI status tokens to Nextflow QueueStatus values'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()
        final text = '''
            1 PENDING
            2 RUNNING
            3 HOLD
            4 DONE
            5 ERROR
        '''.stripIndent(true)

        when:
        final result = executor.parseQueueStatus(text)

        then:
        result == [
            '1': AbstractGridExecutor.QueueStatus.PENDING,
            '2': AbstractGridExecutor.QueueStatus.RUNNING,
            '3': AbstractGridExecutor.QueueStatus.HOLD,
            '4': AbstractGridExecutor.QueueStatus.DONE,
            '5': AbstractGridExecutor.QueueStatus.ERROR,
        ]
    }

    def 'should ignore malformed or unknown status lines'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        when:
        final result = executor.parseQueueStatus('not-a-valid-line\n1 BOGUS\n2 RUNNING\n')

        then:
        result == ['2': AbstractGridExecutor.QueueStatus.RUNNING]
    }

    private TaskRun newTask(Map taskConfig) {
        final task = Mock(TaskRun)
        task.getConfig() >> new TaskConfig(taskConfig)
        return task
    }

    def 'should leave resourceProfile unset by default'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster'])
        executor.register()

        expect:
        executor.resourceProfile == null
        executor.resourceProfileFor(newTask([:])) == null
    }

    def 'should resolve the resource profile from the executor config or ext.resourceProfile'() {
        given:
        final executor = newExecutor([cluster: 'my-cluster', resourceProfile: 'small'])
        executor.register()

        expect:
        executor.resourceProfileFor(newTask([:])) == 'small'
        executor.resourceProfileFor(newTask([ext: [resourceProfile: 'large']])) == 'large'
    }

    def 'should build the submit command line with resources, container and resource profile'() {
        given:
        final executor = newExecutor([cluster: 'k8s', launcherCli: 'wb-launch'], Spy(WorkbenchExecutor))
        executor.register()
        executor.getJobNameFor(_) >> 'nf-foo'
        final task = newTask([cpus: 2, memory: '1 GB', ext: [resourceProfile: 'large']])
        task.getContainer() >> 'python:3.12-slim'

        expect:
        executor.getSubmitCommandLine(task, Paths.get('/work/ab/cd/.command.run')) == [
            'wb-launch', 'submit', '--cluster', 'k8s', '--name', 'nf-foo',
            '--cpus', '2', '--mem-mb', '1024', '--container', 'python:3.12-slim',
            '--resource-profile', 'large', '.command.run',
        ]
    }

    def 'should omit the resource profile flag when none is configured'() {
        given:
        final executor = newExecutor([cluster: 'k8s'], Spy(WorkbenchExecutor))
        executor.register()
        executor.getJobNameFor(_) >> 'nf-foo'

        expect:
        !executor.getSubmitCommandLine(newTask([:]), Paths.get('.command.run')).contains('--resource-profile')
    }

}
