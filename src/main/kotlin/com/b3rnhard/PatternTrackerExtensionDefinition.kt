package com.b3rnhard

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.*

class PatternTrackerExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "pattern-tracker"

    override fun getAuthor(): String = "b3rnhard"

    override fun getVersion(): String = "0.1" // TODO: Consider getting this from pom.xml

    override fun getId(): UUID = DRIVER_ID

    override fun getHardwareVendor(): String = "b3rnhard"

    override fun getHardwareModel(): String = "pattern-tracker"

    override fun getRequiredAPIVersion(): Int = 22 // TODO: Consider getting this from pom.xml dependency

    override fun getNumMidiInPorts(): Int = 0

    override fun getNumMidiOutPorts(): Int = 0

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        // No auto-detection needed
    }

    override fun createInstance(host: ControllerHost): PatternTrackerExtension {
        return PatternTrackerExtension(this, host)
    }

    companion object {
        // Define the private static final UUID as a companion object property
        private val DRIVER_ID: UUID = UUID.fromString("b243d28b-6d55-4184-9caa-55ce3cf5c061")
    }
} 