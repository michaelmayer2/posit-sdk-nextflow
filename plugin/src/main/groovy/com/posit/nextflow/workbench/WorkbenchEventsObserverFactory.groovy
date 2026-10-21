package com.posit.nextflow.workbench

import java.nio.file.Path

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.trace.TraceObserverFactoryV2
import nextflow.trace.TraceObserverV2

/**
 * Creates a {@link WorkbenchEventsObserver} when {@code workbench.eventsFile} is set.
 */
@CompileStatic
class WorkbenchEventsObserverFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        final opts = new WorkbenchConfig((session.config.get('workbench') ?: [:]) as Map)
        if( !opts.eventsFile )
            return []
        final file = Path.of(opts.eventsFile).toAbsolutePath()
        return [new WorkbenchEventsObserver(file)] as Collection<TraceObserverV2>
    }
}
