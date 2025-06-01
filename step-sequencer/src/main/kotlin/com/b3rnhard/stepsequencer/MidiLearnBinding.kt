package com.b3rnhard.stepsequencer

import com.bitwig.extension.controller.api.ControllerHost
import com.bitwig.extension.controller.api.SettableEnumValue

class MidiLearnBinding(
    private val host: ControllerHost,
    private val name: String,
    category: String,
    initialValue: String,
    private val onTrigger: (Int) -> Unit // Callback function to execute when the binding is triggered, now accepts MIDI value
) {

    private var cc: Int = -1
    private var channel: Int = -1
    private var isLearning: Boolean = false
    private lateinit var setting: SettableEnumValue

    init {
        setting = host.preferences.getEnumSetting(
            name,
            category,
            arrayOf("Not Mapped", "Learning..."),
            initialValue
        )

        setting.addValueObserver { value ->
            when (value) {
                "Learning..." -> {
                    isLearning = true
                    host.showPopupNotification("Learning $name - Send a CC message")
                }
                "Not Mapped" -> {
                    cc = -1
                    channel = -1
                    host.showPopupNotification("$name unmapped")
                    isLearning = false // Stop learning if manually set to Not Mapped
                }
                else -> { // Handle saved CC value
                    parseAndSetMidiBinding(value)
                    isLearning = false // Stop learning after parsing saved value
                }
            }
        }
        
        // Trigger observer once on initialization to load any saved value
        setting.get(); 
    }

    private fun parseAndSetMidiBinding(value: String) {
        val parts = value.split(" ")
        if (parts.size == 4 && parts[0] == "CC" && parts[2] == "Ch") {
            try {
                cc = parts[1].toInt()
                channel = parts[3].toInt() - 1 // Channel is 0-indexed internally
                host.showPopupNotification("$name mapped to CC ${cc} Channel ${channel + 1}")
            } catch (e: NumberFormatException) {
                host.println("Error parsing saved $name setting: $value")
                // Optionally reset the setting if parsing fails?
                // setting.set("Not Mapped")
            }
        } else {
             // If the format is unexpected (e.g., manual edit), reset to Not Mapped
             // This might be too aggressive, maybe just log an error?
             // host.println("Unexpected saved $name setting format: $value")
             // setting.set("Not Mapped")
        }
    }

    /**
     * Call this from your MidiIn callback to handle incoming MIDI messages.
     * Returns true if the message was handled by this binding (either learned or triggered),
     * false otherwise.
     */
    fun handleMidiMessage(status: Int, data1: Int, data2: Int): Boolean {
        // Check if it's a CC message
        if (status in 0xB0..0xBF) {
            val messageChannel = status and 0x0F
            val messageCC = data1
            val value = data2

            if (isLearning) {
                // If currently learning, capture the CC and channel
                cc = messageCC
                channel = messageChannel
                setting.set("CC ${cc} Ch ${channel + 1}") // Save the binding as a string
                isLearning = false // Stop learning after capturing
                host.showPopupNotification("$name mapped to CC ${cc} Channel ${channel + 1}")
                return true // Message was handled (learned)
            }

            // If not learning, check if the message matches the learned binding
            if (cc != -1 && channel != -1 && messageCC == cc && messageChannel == channel && value != 0) {
                 // For CC messages, trigger the callback with the value
                onTrigger.invoke(value) 

                return true // Message was handled (triggered)
            }
        }
        return false // Message was not handled by this binding
    }
} 