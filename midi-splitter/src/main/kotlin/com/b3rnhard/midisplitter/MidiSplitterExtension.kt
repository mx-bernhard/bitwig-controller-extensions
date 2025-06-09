package com.b3rnhard.midisplitter

import com.b3rnhard.midisplitter.MidiSplitterExtensionDefinition.Companion.versionFromProperties
import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import com.bitwig.extension.controller.api.MidiIn
import com.bitwig.extension.controller.api.MidiOut

class MidiSplitterExtension(
    definition: ControllerExtensionDefinition,
    host: ControllerHost
) : ControllerExtension(definition, host) {

    private lateinit var midiIn: MidiIn
    private lateinit var midiOut1: MidiOut
    private lateinit var midiOut2: MidiOut

    override fun init() {
        host.println("=== Midi Splitter Extension v${versionFromProperties} Starting ===")

        // Get MIDI ports
        midiIn = getMidiInPort(0)
        midiOut1 = getMidiOutPort(0)
        midiOut2 = getMidiOutPort(1)

        // Set up MIDI callback to forward all messages
        midiIn.setMidiCallback { status: Int, data1: Int, data2: Int ->
            // Forward to both virtual outputs
            midiOut1.sendMidi(status, data1, data2)
            midiOut2.sendMidi(status, data1, data2)
        }

        // Forward SysEx messages as well
        midiIn.setSysexCallback { sysexData: String ->
            midiOut1.sendSysex(sysexData)
            midiOut2.sendSysex(sysexData)
        }

        host.showPopupNotification("MIDI Splitter $versionFromProperties initialized - forwarding to 2 midi outputs")
    }

    override fun exit() {
        host.println("=== Midi Splitter Extension v$versionFromProperties Exited ===")
    }

    override fun flush() {
        // Nothing to flush
    }
} 