package com.b3rnhard.midisplitter

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.UUID

class MidiSplitterExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "MIDI Splitter"
    override fun getAuthor(): String = "Your Name"
    override fun getVersion(): String = "0.1"
    override fun getId(): UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    override fun getHardwareVendor(): String = "Generic"
    override fun getHardwareModel(): String = "MIDI Splitter"
    override fun getRequiredAPIVersion(): Int = 22

    override fun getNumMidiInPorts(): Int = 1  // Real hardware input
    override fun getNumMidiOutPorts(): Int = 2 // Two virtual outputs

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        // Leave empty - manual assignment to any hardware device
    }

    override fun createInstance(host: ControllerHost): MidiSplitterExtension {
        return MidiSplitterExtension(this, host)
    }
} 