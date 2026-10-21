package com.posit.nextflow.workbench

import java.nio.file.Files

import groovy.json.JsonSlurper
import nextflow.Session
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification

class WorkbenchEventsObserverTest extends Specification {

    def 'should create an observer only when eventsFile is set'() {
        given:
        final factory = new WorkbenchEventsObserverFactory()
        final without = Mock(Session) { getConfig() >> [workbench: [cluster: 'k8s']] }
        final with = Mock(Session) { getConfig() >> [workbench: [eventsFile: '/tmp/events.ndjson']] }

        expect:
        factory.create(without).isEmpty()
        factory.create(with).size() == 1
    }

    def 'should append one JSON line per task event, without script and env'() {
        given:
        final dir = Files.createTempDirectory('wb-events')
        final file = dir.resolve('sub/events.ndjson')
        final observer = new WorkbenchEventsObserver(file)
        observer.onFlowCreate(Mock(Session))
        final trace = new TraceRecord()
        trace.put('task_id', 3)
        trace.put('name', 'SQUARE (2)')
        trace.put('native_id', 'job-42')
        trace.put('script', 'echo secret')
        trace.put('env', 'TOKEN=x')

        when:
        observer.onTaskSubmit(new TaskEvent(null, trace))
        observer.onTaskComplete(new TaskEvent(null, trace))
        final lines = Files.readAllLines(file).collect { new JsonSlurper().parseText(it) as Map }

        then:
        lines*.event == ['task_submitted', 'task_completed']
        lines[0].trace == [task_id: 3, name: 'SQUARE (2)', native_id: 'job-42']
        lines[0].utcTime

        cleanup:
        dir.deleteDir()
    }
}
