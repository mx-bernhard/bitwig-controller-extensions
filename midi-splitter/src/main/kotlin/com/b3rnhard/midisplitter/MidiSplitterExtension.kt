package com.b3rnhard.midisplitter

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
    private lateinit var virtualOut1: MidiOut
    private lateinit var virtualOut2: MidiOut

    override fun init() {
        // Get MIDI ports
        midiIn = getMidiInPort(0)
        virtualOut1 = getMidiOutPort(0)
        virtualOut2 = getMidiOutPort(1)

        // Set up MIDI callback to forward all messages
        midiIn.setMidiCallback { status: Int, data1: Int, data2: Int ->
            // Forward to both virtual outputs
            virtualOut1.sendMidi(status, data1, data2)
            virtualOut2.sendMidi(status, data1, data2)
        }

        // Forward SysEx messages as well
        midiIn.setSysexCallback { sysexData: String ->
            virtualOut1.sendSysex(sysexData)
            virtualOut2.sendSysex(sysexData)
        }

        host.showPopupNotification("MIDI Splitter initialized - forwarding to 2 virtual outputs")
    }

    override fun exit() {
        host.showPopupNotification("MIDI Splitter stopped")
    }

    override fun flush() {
        // Nothing to flush
    }
} 