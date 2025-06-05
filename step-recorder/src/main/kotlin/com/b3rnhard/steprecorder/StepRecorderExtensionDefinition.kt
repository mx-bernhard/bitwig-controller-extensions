package com.b3rnhard.steprecorder

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.UUID

class StepRecorderExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "Step Recorder"
    override fun getAuthor(): String = "b3rnhard"
    override fun getVersion(): String = "0.1"
    override fun getId(): UUID = UUID.fromString("6c0e6f1b-4b7a-4c8a-8f1a-5b9a9c3d5a7e") // Generated unique UUID
    override fun getHardwareVendor(): String = "Generic"
    override fun getHardwareModel(): String = "Step Recorder"
    override fun getRequiredAPIVersion(): Int = 22

    override fun getNumMidiInPorts(): Int = 1 // Now we need one MIDI input from virtual port
    override fun getNumMidiOutPorts(): Int = 0

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        // Leave empty - users will manually assign the virtual MIDI port
    }

    override fun createInstance(host: ControllerHost): StepRecorderExtension {
        return StepRecorderExtension(this, host)
    }
}