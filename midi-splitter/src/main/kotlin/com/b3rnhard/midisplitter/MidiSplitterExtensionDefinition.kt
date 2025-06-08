package com.b3rnhard.midisplitter

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.*

class MidiSplitterExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "Midi Splitter"
    override fun getAuthor(): String = "b3rnhard"
    override fun getVersion(): String = version
    override fun getId(): UUID = DRIVER_ID
    override fun getHardwareVendor(): String = "Generic"
    override fun getHardwareModel(): String = "MIDI Splitter"
    override fun getRequiredAPIVersion(): Int = 22

    override fun getNumMidiInPorts(): Int = 1  // Real hardware input
    override fun getNumMidiOutPorts(): Int = 2 // Two virtual outputs

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        list.add(arrayOf("MIDI Splitter"), arrayOf("MIDI Splitter"))
    }

    override fun createInstance(host: ControllerHost): MidiSplitterExtension {
        return MidiSplitterExtension(this, host)
    }

    companion object {
        private val DRIVER_ID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private val version: String by lazy {
            try {
                val props = Properties()
                MidiSplitterExtensionDefinition::class.java.getResourceAsStream("/version.properties").use {
                    props.load(it)
                }
                props.getProperty("version", "0.0.0-dev")
            } catch (e: Exception) {
                "0.0.0-error"
            }
        }
    }
} 