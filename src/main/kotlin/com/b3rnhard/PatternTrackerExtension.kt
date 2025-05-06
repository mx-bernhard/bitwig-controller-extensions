package com.b3rnhard

import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.api.ControllerHost

class PatternTrackerExtension(definition: PatternTrackerExtensionDefinition, host: ControllerHost)
    : ControllerExtension(definition, host) {

    override fun init() {
        val host = host // Access the protected host property

        // TODO: Perform your driver initialization here.
        // For now just show a popup notification for verification that it is running.
        host.showPopupNotification("pattern-tracker Initialized")
    }

    override fun exit() {
        // TODO: Perform any cleanup once the driver exits
        // For now just show a popup notification for verification that it is no longer running.
        host.showPopupNotification("pattern-tracker Exited")
    }

    override fun flush() {
        // TODO Send any updates you need here.
    }
} 