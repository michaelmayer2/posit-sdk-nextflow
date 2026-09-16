package com.posit.nextflow.workbench

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * The plugin entry point. No custom startup/shutdown behavior is needed --
 * {@link WorkbenchExecutor} is registered directly as the plugin's extension point.
 */
@CompileStatic
class WorkbenchPlugin extends BasePlugin {

    WorkbenchPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }
}
