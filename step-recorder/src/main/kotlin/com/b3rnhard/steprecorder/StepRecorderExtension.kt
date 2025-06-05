package com.b3rnhard.steprecorder

import com.bitwig.extension.controller.api.ControllerHost
import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.DocumentState
import com.bitwig.extension.controller.api.Clip
import com.bitwig.extension.controller.api.NoteInput
import com.bitwig.extension.controller.api.CursorTrack
import com.bitwig.extension.controller.api.Transport
import com.bitwig.extension.controller.api.MidiIn
import com.bitwig.extension.controller.api.Application
import com.bitwig.extension.controller.api.Signal
import com.bitwig.extension.controller.api.Preferences
import com.bitwig.extension.controller.api.SettableBooleanValue
import com.bitwig.extension.controller.api.SettableEnumValue

class StepRecorderExtension(definition: ControllerExtensionDefinition, host: ControllerHost) : ControllerExtension(definition, host) {

    private lateinit var documentState: DocumentState
    private lateinit var application: Application
    private lateinit var transport: Transport
    private lateinit var cursorTrack: CursorTrack
    private lateinit var cursorClip: Clip
    private lateinit var midiIn: MidiIn
    private lateinit var noteInput: NoteInput
    private lateinit var stepLengthValueSetting: SettableEnumValue

    private lateinit var tripletSetting: SettableEnumValue
    private lateinit var cursorForwardAction: Signal
    private lateinit var cursorBackwardAction: Signal

    private lateinit var enableSetting: SettableBooleanValue
    private lateinit var preferences: Preferences

    private val midiLearnBindings = mutableListOf<MidiLearnBinding>()

    private lateinit var stepper: Stepper

    private var isUpdatingSelection = false

    // Chord detection
    private val activeNotes = mutableMapOf<Int, Long>()
    private val pendingNotes = mutableSetOf<Int>()
    private var firstNoteTime: Long = 0
    private var lastNoteOnTime: Long = 0
    private val chordThresholdMs = 100L // Notes within 100ms are considered a chord

    override fun init() {
        // Get document state and application
        documentState = host.documentState
        application = host.createApplication()
        transport = host.createTransport()
        preferences = host.preferences

        // Create cursor track that follows selection
        cursorTrack = host.createCursorTrack("steprecorder:CursorTrack", "Cursor Track", 0, 0, true)

        // Create global launcher cursor clip (follows the global clip launcher selection)
        stepper = Stepper(host, { this.cursorClip })
        cursorClip = host.createLauncherCursorClip(stepper.cursorSteps, 128)

        // Mark the clip existence as interested so we can read it later
        cursorClip.exists().markInterested()

        // Mark clip play start position as interested so we can read it
        cursorClip.playStart.markInterested()

        // Mark transport play position as interested to track cursor
        transport.playPosition().markInterested()
        // disable step recording when playing
        transport.isPlaying().markInterested()

        // Add observer to debug clip changes
        cursorClip.exists().addValueObserver { exists ->
            host.println("Clip exists: $exists")
        }

        setupNoteType()
        setupForward()
        setupBackward()
        setupEnable()
        setupNoteValue()
        setupClear()
        setupNoteSelectionObserver()

        updateStepLength()
        updateCursorFromClipOrTransport()

        // Get MIDI input port and set up input/callback (only if a MIDI port is assigned)
        try {
            midiIn = host.getMidiInPort(0)
            noteInput = midiIn.createNoteInput("Keyboard", "80????", "90????", "B0????")
            noteInput.setShouldConsumeEvents(false) // Don't consume events so they can still trigger notes in the DAW

            // Set up a MIDI callback to handle note on/off for chord detection and MIDI learn
            midiIn.setMidiCallback { status: Int, data1: Int, data2: Int ->
                val currentTime = System.currentTimeMillis()

                // Pass CC messages to MIDI learn bindings
                if (midiLearnBindings.any { it.handleMidiMessage(status, data1, data2) }) return@setMidiCallback

                // Check if it's a Note On message (status 0x90-0x9F)
                if (status in 0x90..0x9F) {
                    val note = data1
                    val velocity = data2

                    if (velocity > 0) {
                        // Note On
                        activeNotes[note] = currentTime
                        pendingNotes.add(note)

                        // Track first note time only when starting a new chord
                        if (pendingNotes.size == 1) {
                            firstNoteTime = currentTime
                        }
                        lastNoteOnTime = currentTime
                    } else {
                        // Note Off (velocity 0)
                        activeNotes.remove(note)

                        // If no notes are currently held, process pending notes
                        if (activeNotes.isEmpty()) {
                            processPendingNotes()
                        }
                    }
                }
                // Handle explicit Note Off messages (status 0x80-0x8F)
                else if (status in 0x80..0x8F) {
                    val note = data1
                    activeNotes.remove(note)

                    // If no notes are currently held, process pending notes
                    if (activeNotes.isEmpty()) {
                        processPendingNotes()
                    }
                }
            }

            host.showPopupNotification("Step Recorder Initialized with MIDI Input")
        } catch (_: Exception) {
            host.showPopupNotification("Step Recorder Initialized (No MIDI Input Assigned)")
            host.println("No MIDI input assigned - MIDI learn and note input features disabled")
        }

        host.showPopupNotification("Use controller preferences to learn forward/backward buttons!")
    }

    private fun updateStepLength() {
        stepper.updateNoteLengthInIntegerRepresentation(stepLengthValueSetting.get(), tripletSetting.get())
    }

    private fun setupNoteType() {
        tripletSetting = documentState.getEnumSetting(
            "Note Type",
            "Step Recorder",
            arrayOf("Regular", "Triplet"),
            "Regular"
        )
        tripletSetting.addValueObserver { updateStepLength() }
    }

    private fun setupBackward() {
        cursorBackwardAction = documentState.getSignalSetting("Cursor Backward", "Step Recorder", "Backward")
        cursorBackwardAction.addSignalObserver {
            stepper.backward()
            updateCursorSelection()
        }
        midiLearnBindings.add(MidiLearnBinding(host, "Backward Button", "MIDI Learn", "Not Mapped") {
            stepper.backward()
            updateCursorSelection()
        })
    }

    private fun setupForward() {
        cursorForwardAction = documentState.getSignalSetting("Cursor Forward", "Step Recorder", "Forward")

        cursorForwardAction.addSignalObserver {
            stepper.forward()
            updateCursorSelection()
        }

        midiLearnBindings.add(MidiLearnBinding(host, "Forward Button", "MIDI Learn", "Not Mapped") {
            stepper.forward()
            updateCursorSelection()
        })
    }

    private fun setupClear() {
        val cursorClearAction = documentState.getSignalSetting("Clear at Cursor", "Step Recorder", "Clear")

        cursorClearAction.addSignalObserver {
            clearNotesAtCursor()
        }

        midiLearnBindings.add(MidiLearnBinding(host, "Clear Button", "MIDI Learn", "Not Mapped") {
            clearNotesAtCursor()
        })
    }

    private fun setupNoteValue() {
        stepLengthValueSetting = documentState.getEnumSetting(
            "Step Length", // name
            "Step Recorder", // category
            arrayOf(
                "32/1", "16/1", "8/1", "4/1", "2/1", "1/1", // Whole note and longer
                "1/2", "1/4", "1/8", "1/16", "1/32", "1/64" // Half note and shorter
            ),
            "1/16" // initial value (16th note)
        )

        stepLengthValueSetting.addValueObserver { updateStepLength() }

        midiLearnBindings.add(MidiLearnBinding(
            host, "Step Length Value Control", "MIDI Learn", "Not Mapped",
            { changeStepLengthValue(it) }))
    }

    private fun setupEnable() {
        enableSetting = documentState.getBooleanSetting("Enable step recording", "Step Recorder", false)

        midiLearnBindings.add(MidiLearnBinding(host, "Enable/Disable", "MIDI Learn", "Not Mapped") {
            enableSetting.set(!enableSetting.get())
            val text = if (enableSetting.get()) "Enabled" else "Disabled"
            host.showPopupNotification(text)
        })
    }

    private fun changeStepLengthValue(value: Int) {
        // This binding triggers when a CC message matching the learned value control is received.
        // The MidiLearnBinding class handles mapping the CC value to the preference string.
        // The noteValueSetting's existing observer handles updating the noteLength.
        // So, we don't need to do anything specific here, the observer linkage handles it.
        val noteValueIndex = mapCCToNoteValueIndex(value) // Use the received MIDI value
        val noteValues = arrayOf(
            "32/1", "16/1", "8/1", "4/1", "2/1", "1/1",
            "1/2", "1/4", "1/8", "1/16", "1/32", "1/64"
        )
        if (noteValueIndex in noteValues.indices) {
            stepLengthValueSetting.set(noteValues[noteValueIndex])
            host.showPopupNotification("Note Value: ${noteValues[noteValueIndex]}")
        }
    }

    private fun setupNoteSelectionObserver() {
        // Add observer for note step selection changes
        cursorClip.addNoteStepObserver { isNoteStepOn ->
            if (!isUpdatingSelection && cursorClip.exists().get()) {
                updateCursorFromSelectedNotes()
            }
        }
    }

    private fun updateCursorSelection() {
        // Visual feedback: select notes at current cursor position
        if (!cursorClip.exists().get()) return

        isUpdatingSelection = true
        try {
            // Clear all selections by selecting step 0 with clearCurrentSelection=true
            cursorClip.selectStepContents(0, 0, 0, true) // Clear all selections

            // Now select all notes at the cursor position (iterate through all possible keys)
            for (key in 0..127) {
                // Select each note that might exist at this position
                cursorClip.selectStepContents(0, stepper.x, key, false) // Don't clear previous selections
            }
        } catch (e: Exception) {
            host.println(e.stackTraceToString())
        } finally {
            isUpdatingSelection = false
        }
    }

    private fun updateCursorFromSelectedNotes() {
        // Find the smallest start position of any selected notes
        // This is complex to implement properly, so for now just a placeholder
        // Would need to iterate through all steps and check selection state
    }

    private fun processPendingNotes() {
        if (pendingNotes.isNotEmpty()) {
            // Check if all notes were played close together (chord) or separately
            val noteList = pendingNotes.sorted()

            if (noteList.size == 1) {
                // Single note
                addNotesToCurrentClip(noteList, 127) // Use default velocity
            } else {
                // Multiple notes - check if they should be a chord
                val noteTimeSpan = lastNoteOnTime - firstNoteTime

                if (noteTimeSpan <= chordThresholdMs) {
                    // Notes were pressed close together - place as chord
                    addNotesToCurrentClip(noteList, 127)
                } else {
                    // Notes were pressed far apart - place sequentially
                    noteList.forEach { note ->
                        addNotesToCurrentClip(listOf(note), 127)
                    }
                }
            }

            pendingNotes.clear()
        }
    }

    private fun addNotesToCurrentClip(notes: List<Int>, velocity: Int) {
        // Check if we have a valid clip
        if (!cursorClip.exists().get()) {
            host.showPopupNotification("No active clip found. Create or select a clip and open piano roll.")
            return
        }
        if (transport.isPlaying().get()) {
            host.println("Step recording disabled, transport is playing")
            return
        }

        try {
            // Clear all existing notes at this step position
            host.println("Clear at ${stepper.x}")
            cursorClip.clearStepsAtX(0, stepper.x)

            // Add all notes at the same position (chord)
            notes.forEach { note ->
                host.println("setStep y:${note}, x:${stepper.x}")
                // - 0.01 prevents note lengths too close to the next and clearing deletes both
                val nl = stepper.noteLengthInBeats - 0.0001
                cursorClip.setStep(0, stepper.x, note, velocity, nl)
            }

            stepper.forward()
            updateCursorSelection()

            val noteNames = notes.joinToString(", ") { getNoteNameFromMidi(it) }
            if (notes.size == 1) {
                host.println("Added $noteNames at step ${stepper.x}")
            } else {
                host.showPopupNotification("Added chord [${noteNames}] at step ${stepper.x}")
            }

        } catch (e: Exception) {
            host.showPopupNotification("Error adding notes: ${e.message}")
            host.println("Error details: $e")
        }
    }

    // Helper function to convert MIDI note number to note name
    private fun getNoteNameFromMidi(midiNote: Int): String {
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (midiNote / 12) - 1
        val noteIndex = midiNote % 12
        return "${noteNames[noteIndex]}$octave"
    }

    override fun exit() {
        host.showPopupNotification("Step Recorder Exited")
    }

    override fun flush() {
        // Nothing specific to flush
    }

    private fun updateCursorFromClipOrTransport() {
        stepper.setXFromBeats(
            if (cursorClip.exists().get())
                cursorClip.playStart.get()
            else
                transport.playPosition().get()
        )
    }

    /**
     * Map CC value (0-127) to note value index (0-11)
     * Divides the CC range into 12 equal segments for the 12 note values
     */
    private fun mapCCToNoteValueIndex(ccValue: Int): Int {
        // Clamp CC value to valid range
        val clampedCC = ccValue.coerceIn(0, 127)

        // Map to index 0-11 (12 note values total)
        // Using 127/11 ≈ 11.55, so each segment is about 10.6 CC values wide
        return (clampedCC * 11 / 127).coerceIn(0, 11)
    }

    /**
     * Clear all notes at the current cursor position
     * Converts note positions into rests/gaps
     */
    private fun clearNotesAtCursor() {
        // Check if we have a valid clip
        if (!cursorClip.exists().get()) {
            host.showPopupNotification("No active clip found. Create or select a clip and open piano roll.")
            return
        }

        try {
            cursorClip.clearStepsAtX(0, stepper.x)

            host.println("Cleared notes at beat ${stepper.x}, step ${stepper.cursorPosition}")

        } catch (e: Exception) {
            host.showPopupNotification("Error clearing notes: ${e.message}")
            host.println("Error details: $e")
        }
    }

    companion object {
        @JvmStatic
        fun createExtensionInstance(definition: ControllerExtensionDefinition, host: ControllerHost): ControllerExtension {
            return StepRecorderExtension(definition, host)
        }
    }
}