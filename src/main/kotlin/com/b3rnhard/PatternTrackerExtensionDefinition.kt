package com.b3rnhard

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.*

class PatternTrackerExtensionDefinition : ControllerExtensionDefinition() {

    // Metadata from TypeScript
    override fun getName(): String = "Tracker Pattern Trigger" // Updated name
    override fun getAuthor(): String = "b3rnhard" // Kept original author
    override fun getVersion(): String = SCRIPT_VERSION
    override fun getId(): UUID = DRIVER_ID
    override fun getHardwareVendor(): String = "Generic" // From TS
    override fun getHardwareModel(): String = "Tracker Pattern Trigger" // Match name
    override fun getRequiredAPIVersion(): Int = 22

    // Ports (from TS)
    override fun getNumMidiInPorts(): Int = 0
    override fun getNumMidiOutPorts(): Int = 0

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        // No auto-detection needed (as per TS)
    }

    override fun createInstance(host: ControllerHost): PatternTrackerExtension {
        return PatternTrackerExtension(this, host)
    }

    companion object {
        // Use const val for compile-time constants where possible
        const val SCRIPT_VERSION = "0.2.1"
        const val DEVICES_GROUP_NAME = "Devices"
        const val FIRE_PATTERN_GROUP_NAME = "Patterns"
        const val MAX_TRACKS = 5 // Max tracks to search in child banks (as per TS)
        const val MAX_SLOTS = 5 // Max slots per track (as per TS)

        // Keep the original UUID
        private val DRIVER_ID: UUID = UUID.fromString("b243d28b-6d55-4184-9caa-55ce3cf5c061")
    }
} 