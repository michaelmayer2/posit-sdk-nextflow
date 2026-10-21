package com.posit.nextflow.workbench

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent

/**
 * Appends workflow and task events to {@code workbench.eventsFile}, one JSON object per line,
 * so that a tool such as the Positron Nextflow extension can follow a run by tailing a file.
 * A file works the same whether Nextflow runs in the user's session or as a headless Workbench
 * Job on the shared filesystem, and survives the reader restarting, unlike an HTTP callback.
 *
 * Each line has {@code event} and {@code utcTime}, plus {@code trace} (the task's trace record)
 * for task events or {@code workflow} for workflow events. Event names: {@code started},
 * {@code task_submitted}, {@code task_started}, {@code task_completed}, {@code task_cached},
 * {@code error}, {@code completed}.
 *
 * Not tied to the {@code workbench} executor: load the plugin with
 * {@code -plugins nf-workbench@<version>} to get events for any executor.
 */
@Slf4j
@CompileStatic
class WorkbenchEventsObserver implements TraceObserverV2 {

    /** Large or sensitive trace fields that a run monitor does not need. */
    private static final Set<String> OMITTED_FIELDS = ['script', 'env'] as Set

    private final Path file
    private Session session

    WorkbenchEventsObserver(Path file) {
        this.file = file
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
        if( file.parent )
            Files.createDirectories(file.parent)
    }

    @Override
    void onFlowBegin() {
        write([event: 'started', workflow: workflowInfo()] as Map<String,Object>)
    }

    @Override
    void onTaskSubmit(TaskEvent event) { writeTask('task_submitted', event) }

    @Override
    void onTaskStart(TaskEvent event) { writeTask('task_started', event) }

    @Override
    void onTaskComplete(TaskEvent event) { writeTask('task_completed', event) }

    @Override
    void onTaskCached(TaskEvent event) { writeTask('task_cached', event) }

    @Override
    void onFlowError(TaskEvent event) {
        final Map<String,Object> message = [event: 'error', workflow: workflowInfo()] as Map<String,Object>
        if( event?.trace )
            message.trace = traceInfo(event.trace)
        write(message)
    }

    @Override
    void onFlowComplete() {
        write([event: 'completed', workflow: workflowInfo()] as Map<String,Object>)
    }

    private void writeTask(String name, TaskEvent event) {
        final trace = event.trace ?: event.handler?.getTraceRecord()
        if( trace )
            write([event: name, trace: traceInfo(trace)] as Map<String,Object>)
    }

    @PackageScope static Map<String,Object> traceInfo(TraceRecord trace) {
        final result = new LinkedHashMap<String,Object>()
        trace.store.each { String key, Object value ->
            if( value != null && !OMITTED_FIELDS.contains(key) )
                result.put(key, jsonValue(value))
        }
        return result
    }

    private Map<String,Object> workflowInfo() {
        final meta = session?.getWorkflowMetadata()
        if( !meta )
            return [:]
        final result = new LinkedHashMap<String,Object>()
        result.runName = meta.runName
        result.sessionId = meta.sessionId?.toString()
        result.profile = meta.profile
        result.launchDir = meta.launchDir?.toString()
        result.workDir = meta.workDir?.toString()
        result.projectDir = meta.projectDir?.toString()
        result.scriptFile = meta.scriptFile?.toString()
        result.commandLine = meta.commandLine
        result.resume = meta.resume
        result.start = meta.start?.toString()
        result.complete = meta.complete?.toString()
        result.success = meta.success
        result.exitStatus = meta.exitStatus
        // errorMessage is the failed task's stderr, which may be empty;
        // errorReport has Nextflow's own explanation of the failure
        result.errorMessage = meta.errorMessage ?: session.error?.message
        result.errorReport = meta.errorReport
        return result
    }

    private static Object jsonValue(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof String
            ? value
            : value.toString()
    }

    @PackageScope synchronized void write(Map<String,Object> message) {
        final Map<String,Object> stamped = new LinkedHashMap<String,Object>()
        stamped.utcTime = new Date().toInstant().toString()
        stamped.putAll(message)
        final line = JsonOutput.toJson(stamped) + '\n'
        try {
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        catch( IOException e ) {
            log.warn1 "[WORKBENCH] cannot write events to ${file}: ${e.message}"
        }
    }
}
