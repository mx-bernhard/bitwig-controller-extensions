package com.b3rnhard

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.*

class PatternTrackerExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "Pattern Tracker"
    override fun getAuthor(): String = "b3rnhard"
    override fun getVersion(): String {
        return versionFromProperties
    }
    override fun getId(): UUID = UUID.fromString("b243d28b-6d55-4184-9caa-55ce3cf5c061")
    override fun getHardwareVendor(): String = "Generic"
    override fun getHardwareModel(): String = "Tracker Pattern Trigger"
    override fun getRequiredAPIVersion(): Int = 22

    override fun getNumMidiInPorts(): Int = 0
    override fun getNumMidiOutPorts(): Int = 0

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
    }

    override fun createInstance(host: ControllerHost): PatternTrackerExtension {
        return PatternTrackerExtension(this, host)
    }

    companion object {
        val versionFromProperties: String by lazy {
            try {
                val props = Properties()
                PatternTrackerExtensionDefinition::class.java.getResourceAsStream("/version.properties").use {
                    props.load(it)
                }
                props.getProperty("version", "0.0.0-dev")
            } catch (e: Exception) {
                "0.0.0-error"
            }
        }
    }
}