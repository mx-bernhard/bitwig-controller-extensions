package com.b3rnhard.keyboardnotecreator

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.UUID

class KeyboardNoteCreatorExtensionDefinition : ControllerExtensionDefinition() {

    override fun getName(): String = "Keyboard Note Creator"
    override fun getAuthor(): String = "Your Name"
    override fun getVersion(): String = "0.1"
    override fun getId(): UUID = UUID.fromString("6c0e6f1b-4b7a-4c8a-8f1a-5b9a9c3d5a7e") // Generated unique UUID
    override fun getHardwareVendor(): String = "Generic"
    override fun getHardwareModel(): String = "Keyboard Note Creator"
    override fun getRequiredAPIVersion(): Int = 22

    override fun getNumMidiInPorts(): Int = 1 // We need one MIDI input port for the keyboard
    override fun getNumMidiOutPorts(): Int = 0

    override fun listAutoDetectionMidiPortNames(
        list: AutoDetectionMidiPortNamesList,
        platformType: PlatformType
    ) {
        // TODO: Implement auto-detection if needed
    }

    override fun createInstance(host: ControllerHost): KeyboardNoteCreatorExtension {
        return KeyboardNoteCreatorExtension(this, host)
    }
} 