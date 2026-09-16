package com.posit.nextflow.workbench

import nextflow.exception.AbortOperationException
import nextflow.executor.AbstractGridExecutor
import nextflow.executor.ExecutorConfig
import spock.lang.Specification

class WorkbenchExecutorTest extends Specification {

    private WorkbenchExecutor newExecutor(Map executorOpts) {
        final executor = new WorkbenchExecutor()
        executor.name = 'workbench'
        executor.config = new ExecutorConfig(executorOpts)
        return executor
    }

    def 'should require a cluster to be configured'() {
        given:
        final executor = newExecutor(['$workbench': [:]])

        when:
        executor.register()

        then:
        thrown(AbortOperationException)
    }

    def 'should read cluster and launcherCli from the executor.$workbench config scope'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster', launcherCli: '/opt/bin/my-launcher']])

        when:
        executor.register()

        then:
        noExceptionThrown()
        executor.cluster == 'my-cluster'
        executor.launcherCli == '/opt/bin/my-launcher'
    }

    def 'should default launcherCli when not configured'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster']])

        when:
        executor.register()

        then:
        executor.launcherCli == 'posit-workbench-nf-launcher'
    }

    def 'should build the kill command with no ids appended'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster', launcherCli: 'wb-launch']])
        executor.register()

        expect:
        executor.getKillCommand() == ['wb-launch', 'kill']
    }

    def 'should build a single queue status command with no queue filtering'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster', launcherCli: 'wb-launch']])
        executor.register()

        expect:
        executor.queueStatusCommand(null) == ['wb-launch', 'status']
        executor.queueStatusCommand('some-queue') == ['wb-launch', 'status']
    }

    def 'should parse a job id from the submit output'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster']])
        executor.register()

        expect:
        executor.parseJobId('42\n') == '42'
    }

    def 'should reject an empty submit response'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster']])
        executor.register()

        when:
        executor.parseJobId('   \n')

        then:
        thrown(IllegalStateException)
    }

    def 'should map CLI status tokens to Nextflow QueueStatus values'() {
        given:
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster']])
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
        final executor = newExecutor(['$workbench': [cluster: 'my-cluster']])
        executor.register()

        when:
        final result = executor.parseQueueStatus('not-a-valid-line\n1 BOGUS\n2 RUNNING\n')

        then:
        result == ['2': AbstractGridExecutor.QueueStatus.RUNNING]
    }

}
