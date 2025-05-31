package com.b3rnhard.stepsequencer

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.api.ControllerHost
import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.DocumentState
import com.bitwig.extension.controller.api.Track
import com.bitwig.extension.controller.api.ClipLauncherSlot
import com.bitwig.extension.controller.api.Clip
import com.bitwig.extension.controller.api.NoteInput
import com.bitwig.extension.controller.api.CursorTrack
import com.bitwig.extension.controller.api.Transport
import com.bitwig.extension.controller.api.SettableBeatTimeValue
import com.bitwig.extension.controller.api.MidiIn
import com.bitwig.extension.controller.api.SettableDoubleValue
import com.bitwig.extension.controller.api.SettableRangedValue
import com.bitwig.extension.controller.api.Application
import com.bitwig.extension.api.util.midi.ShortMidiMessage
import java.util.*
import com.bitwig.extension.controller.api.Signal
import com.bitwig.extension.controller.api.Preferences
import com.bitwig.extension.controller.api.SettableEnumValue

class StepSequencerExtension(definition: ControllerExtensionDefinition, host: ControllerHost) : ControllerExtension(definition, host) {

    private lateinit var documentState: DocumentState
    private lateinit var application: Application
    private lateinit var transport: Transport
    private lateinit var cursorTrack: CursorTrack
    private lateinit var cursorClip: Clip
    private lateinit var midiIn: MidiIn
    private lateinit var noteInput: NoteInput
    private lateinit var noteValueSetting: SettableEnumValue
    private lateinit var tripletSetting: SettableEnumValue
    private lateinit var cursorForwardAction: Signal
    private lateinit var cursorBackwardAction: Signal
    private lateinit var preferences: Preferences
    private lateinit var midiLearnForwardSetting: SettableEnumValue
    private lateinit var midiLearnBackwardSetting: SettableEnumValue
    private lateinit var midiLearnNoteValueSetting: SettableEnumValue
    private lateinit var midiLearnClearSetting: SettableEnumValue
    
    // MIDI learn state
    private var isLearningForward = false
    private var isLearningBackward = false
    private var isLearningNoteValue = false
    private var isLearningClear = false
    private var forwardCC = -1
    private var forwardChannel = -1
    private var backwardCC = -1
    private var backwardChannel = -1
    private var noteValueCC = -1
    private var noteValueChannel = -1
    private var clearCC = -1
    private var clearChannel = -1
    
    private var noteLength: Double = 0.25 // Default to 16th note
    private var cursorPosition: Double = 0.0
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
        cursorTrack = host.createCursorTrack("stepsequencer:CursorTrack", "Cursor Track", 0, 0, true)

        // Create global launcher cursor clip (follows the global clip launcher selection)
        cursorClip = host.createLauncherCursorClip(128, 128)

        // Mark the clip existence as interested so we can read it later
        cursorClip.exists().markInterested()
        
        // Mark clip play start position as interested so we can read it
        cursorClip.getPlayStart().markInterested()
        
        // Mark transport play position as interested to track cursor
        transport.playPosition().markInterested()
        
        // Add observer to debug clip changes
        cursorClip.exists().addValueObserver { exists ->
            host.showPopupNotification("Clip exists: $exists")
        }

        // Create a setting for note length using document state
        noteValueSetting = documentState.getEnumSetting(
            "Note Value", // name
            "Step Sequencer", // category  
            arrayOf(
                "32/1", "16/1", "8/1", "4/1", "2/1", "1/1", // Whole note and longer
                "1/2", "1/4", "1/8", "1/16", "1/32", "1/64" // Half note and shorter
            ),
            "1/16" // initial value (16th note)
        )
        
        tripletSetting = documentState.getEnumSetting(
            "Note Type",
            "Step Sequencer",
            arrayOf("Regular", "Triplet"),
            "Regular"
        )

        // Add observers to update noteLength when either setting changes
        val updateNoteLength = {
            noteLength = calculateNoteLengthInBeats(noteValueSetting.get(), tripletSetting.get())
            host.showPopupNotification("Note Length: ${noteValueSetting.get()} ${tripletSetting.get()} (${String.format("%.3f", noteLength)} beats)")
        }
        
        noteValueSetting.addValueObserver { updateNoteLength() }
        tripletSetting.addValueObserver { updateNoteLength() }

        // Initialize noteLength with current setting values
        noteLength = calculateNoteLengthInBeats(noteValueSetting.get(), tripletSetting.get())

        // Setup MIDI learn preferences
        setupMidiLearnSettings()

        // Initialize cursor position to current clip start position if clip exists, otherwise transport position
        updateCursorFromClipOrTransport()

        // Create cursor movement actions that can be triggered from document state  
        cursorForwardAction = documentState.getSignalSetting("Cursor Forward", "Step Sequencer", "Forward")
        cursorBackwardAction = documentState.getSignalSetting("Cursor Backward", "Step Sequencer", "Backward")
        
        val cursorClearAction = documentState.getSignalSetting("Clear at Cursor", "Step Sequencer", "Clear")

        // Add observers for cursor movement
        cursorForwardAction.addSignalObserver {
            cursorPosition = (cursorPosition + noteLength).coerceAtLeast(0.0)
            host.showPopupNotification("Cursor moved forward to: ${String.format("%.3f", cursorPosition)} beats")
            updateCursorSelection()
        }

        cursorBackwardAction.addSignalObserver {
            cursorPosition = (cursorPosition - noteLength).coerceAtLeast(0.0)
            host.showPopupNotification("Cursor moved backward to: ${String.format("%.3f", cursorPosition)} beats")
            updateCursorSelection()
        }
        
        cursorClearAction.addSignalObserver {
            clearNotesAtCursor()
        }

        // Get MIDI input port and set up input/callback (only if a MIDI port is assigned)
        try {
            midiIn = host.getMidiInPort(0)
            noteInput = midiIn.createNoteInput("Keyboard", "80????", "90????", "B0????")
            noteInput.setShouldConsumeEvents(false) // Don't consume events so they can still trigger notes in the DAW

            // Set up a MIDI callback to handle note on/off for chord detection
            midiIn.setMidiCallback { status: Int, data1: Int, data2: Int ->
                val currentTime = System.currentTimeMillis()
                
                // Handle MIDI learn for CC messages
                if (status in 0xB0..0xBF) { // CC message
                    val channel = status and 0x0F
                    val cc = data1
                    val value = data2
                    
                    if (isLearningForward) {
                        forwardCC = cc
                        forwardChannel = channel
                        isLearningForward = false
                        midiLearnForwardSetting.set("CC $cc Ch ${channel + 1}")
                        host.showPopupNotification("Forward mapped to CC $cc Channel ${channel + 1}")
                        return@setMidiCallback
                    }
                    
                    if (isLearningBackward) {
                        backwardCC = cc
                        backwardChannel = channel
                        isLearningBackward = false
                        midiLearnBackwardSetting.set("CC $cc Ch ${channel + 1}")
                        host.showPopupNotification("Backward mapped to CC $cc Channel ${channel + 1}")
                        return@setMidiCallback
                    }
                    
                    if (isLearningNoteValue) {
                        noteValueCC = cc
                        noteValueChannel = channel
                        isLearningNoteValue = false
                        midiLearnNoteValueSetting.set("CC $cc Ch ${channel + 1}")
                        host.showPopupNotification("Note Value Control mapped to CC $cc Channel ${channel + 1}")
                        return@setMidiCallback
                    }
                    
                    if (isLearningClear) {
                        clearCC = cc
                        clearChannel = channel
                        isLearningClear = false
                        midiLearnClearSetting.set("CC $cc Ch ${channel + 1}")
                        host.showPopupNotification("Clear Button mapped to CC $cc Channel ${channel + 1}")
                        return@setMidiCallback
                    }
                    
                    // Check if this CC matches learned forward/backward controls
                    if (cc == forwardCC && channel == forwardChannel && value > 0) {
                        cursorPosition = (cursorPosition + noteLength).coerceAtLeast(0.0)
                        host.showPopupNotification("Cursor moved forward to: ${String.format("%.3f", cursorPosition)} beats")
                        updateCursorSelection()
                        return@setMidiCallback
                    }
                    
                    if (cc == backwardCC && channel == backwardChannel && value > 0) {
                        cursorPosition = (cursorPosition - noteLength).coerceAtLeast(0.0)
                        host.showPopupNotification("Cursor moved backward to: ${String.format("%.3f", cursorPosition)} beats")
                        updateCursorSelection()
                        return@setMidiCallback
                    }
                    
                    // Check if this CC matches learned note value control
                    if (cc == noteValueCC && channel == noteValueChannel) {
                        val noteValueIndex = mapCCToNoteValueIndex(value)
                        val noteValues = arrayOf(
                            "32/1", "16/1", "8/1", "4/1", "2/1", "1/1",
                            "1/2", "1/4", "1/8", "1/16", "1/32", "1/64"
                        )
                        if (noteValueIndex in noteValues.indices) {
                            noteValueSetting.set(noteValues[noteValueIndex])
                            host.showPopupNotification("Note Value: ${noteValues[noteValueIndex]}")
                        }
                        return@setMidiCallback
                    }
                    
                    // Check if this CC matches learned clear control
                    if (cc == clearCC && channel == clearChannel && value > 0) {
                        clearNotesAtCursor()
                        return@setMidiCallback
                    }
                }
                
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
            
            host.showPopupNotification("Step Sequencer Initialized with MIDI Input")
        } catch (e: Exception) {
            host.showPopupNotification("Step Sequencer Initialized (No MIDI Input Assigned)")
            host.println("No MIDI input assigned - MIDI learn and note input features disabled")
        }

        host.showPopupNotification("Use controller preferences to learn forward/backward buttons!")
        
        // Set up note selection observer to track selected notes and update cursor position
        setupNoteSelectionObserver()
    }
    
    private fun setupMidiLearnSettings() {
        // MIDI learn for forward button
        midiLearnForwardSetting = preferences.getEnumSetting(
            "Forward Button",
            "MIDI Learn",
            arrayOf("Not Mapped", "Learning..."),
            "Not Mapped"
        )
        
        midiLearnForwardSetting.addValueObserver { value ->
            when (value) {
                "Learning..." -> {
                    isLearningForward = true
                    host.showPopupNotification("Learning Forward Button - Send a CC message")
                }
                "Not Mapped" -> {
                    forwardCC = -1
                    forwardChannel = -1
                    host.showPopupNotification("Forward button unmapped")
                }
            }
        }
        
        // MIDI learn for backward button
        midiLearnBackwardSetting = preferences.getEnumSetting(
            "Backward Button",
            "MIDI Learn",
            arrayOf("Not Mapped", "Learning..."),
            "Not Mapped"
        )
        
        midiLearnBackwardSetting.addValueObserver { value ->
            when (value) {
                "Learning..." -> {
                    isLearningBackward = true
                    host.showPopupNotification("Learning Backward Button - Send a CC message")
                }
                "Not Mapped" -> {
                    backwardCC = -1
                    backwardChannel = -1
                    host.showPopupNotification("Backward button unmapped")
                }
            }
        }
        
        // MIDI learn for note value control
        midiLearnNoteValueSetting = preferences.getEnumSetting(
            "Note Value Control",
            "MIDI Learn",
            arrayOf("Not Mapped", "Learning..."),
            "Not Mapped"
        )
        
        midiLearnNoteValueSetting.addValueObserver { value ->
            when (value) {
                "Learning..." -> {
                    isLearningNoteValue = true
                    host.showPopupNotification("Learning Note Value Control - Send a CC message")
                }
                "Not Mapped" -> {
                    noteValueCC = -1
                    noteValueChannel = -1
                    host.showPopupNotification("Note value control unmapped")
                }
            }
        }
        
        // MIDI learn for clear control
        midiLearnClearSetting = preferences.getEnumSetting(
            "Clear Button",
            "MIDI Learn",
            arrayOf("Not Mapped", "Learning..."),
            "Not Mapped"
        )
        
        midiLearnClearSetting.addValueObserver { value ->
            when (value) {
                "Learning..." -> {
                    isLearningClear = true
                    host.showPopupNotification("Learning Clear Button - Send a CC message")
                }
                "Not Mapped" -> {
                    clearCC = -1
                    clearChannel = -1
                    host.showPopupNotification("Clear button unmapped")
                }
            }
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
            val stepSize = 0.25 // 16th note base step size
            val absoluteStepIndex = (cursorPosition / stepSize).toInt()
            
            // Keep step index within the valid range (0-127)
            val stepIndex = absoluteStepIndex % 128
            
            // Clear all selections by selecting step 0 with clearCurrentSelection=true
            cursorClip.selectStepContents(0, 0, 0, true) // Clear all selections
            
            // Now select all notes at the cursor position (iterate through all possible keys)
            for (key in 0..127) {
                // Select each note that might exist at this position
                cursorClip.selectStepContents(0, stepIndex, key, false) // Don't clear previous selections
            }
            
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

        try {
            // Use our own cursor position for note placement
            val positionInBeats = cursorPosition
            
            // Convert beat position to step index for setStep method
            val stepSize = 0.25 // Use 16th note as base step size for grid
            val absoluteStepIndex = (positionInBeats / stepSize).toInt()
            
            // Keep step index within the valid range (0-127)
            // For longer sequences, we'll just wrap around the visible grid
            val stepIndex = absoluteStepIndex % 128
            
            // Clear all existing notes at this step position (replace functionality)
            cursorClip.clearStepsAtX(0, stepIndex)
            
            // Add all notes at the same position (chord)
            notes.forEach { note ->
                cursorClip.setStep(0, stepIndex, note, velocity, noteLength)
            }
            
            // Advance cursor position by the note length (only once, not per note)
            cursorPosition += noteLength
            
            // Update visual selection to show new cursor position
            updateCursorSelection()
            
            val noteNames = notes.map { getNoteNameFromMidi(it) }.joinToString(", ")
            if (notes.size == 1) {
                host.showPopupNotification("Added ${noteNames} at beat ${String.format("%.3f", positionInBeats)}, step $stepIndex")
            } else {
                host.showPopupNotification("Added chord [${noteNames}] at beat ${String.format("%.3f", positionInBeats)}, step $stepIndex")
            }
            
        } catch (e: Exception) {
            host.showPopupNotification("Error adding notes: ${e.message}")
            host.println("Error details: ${e}")
        }
    }

    private fun addNoteToCurrentClip(note: Int, velocity: Int) {
        // Legacy function - now just calls the new multi-note function
        addNotesToCurrentClip(listOf(note), velocity)
    }

    // Helper function to convert MIDI note number to note name
    private fun getNoteNameFromMidi(midiNote: Int): String {
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (midiNote / 12) - 1
        val noteIndex = midiNote % 12
        return "${noteNames[noteIndex]}$octave"
    }

    override fun exit() {
        host.showPopupNotification("Step Sequencer Exited")
    }

    override fun flush() {
        // Nothing specific to flush
    }

    private fun updateCursorFromClipOrTransport() {
        if (cursorClip.exists().get()) {
            cursorPosition = cursorClip.getPlayStart().get()
        } else {
            cursorPosition = transport.playPosition().get()
        }
    }
    
    /**
     * Calculate note length in beats based on note value and triplet setting
     * Assumes 4/4 time signature where 1 whole note = 4 beats
     */
    private fun calculateNoteLengthInBeats(noteValue: String, noteType: String): Double {
        // Calculate base note length in beats (4/4 time signature)
        val baseLength = when (noteValue) {
            "32/1" -> 128.0  // 32 whole notes
            "16/1" -> 64.0   // 16 whole notes
            "8/1" -> 32.0    // 8 whole notes
            "4/1" -> 16.0    // 4 whole notes
            "2/1" -> 8.0     // 2 whole notes
            "1/1" -> 4.0     // 1 whole note
            "1/2" -> 2.0     // Half note
            "1/4" -> 1.0     // Quarter note
            "1/8" -> 0.5     // Eighth note
            "1/16" -> 0.25   // Sixteenth note
            "1/32" -> 0.125  // Thirty-second note
            "1/64" -> 0.0625 // Sixty-fourth note
            else -> 0.25     // Default to 16th note
        }
        
        // Apply triplet timing if selected (2/3 of regular note length)
        return if (noteType == "Triplet") {
            baseLength * (2.0 / 3.0)
        } else {
            baseLength
        }
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
            // Convert cursor position to step index
            val stepSize = 0.25 // Use 16th note as base step size for grid
            val absoluteStepIndex = (cursorPosition / stepSize).toInt()
            
            // Keep step index within the valid range (0-127)
            val stepIndex = absoluteStepIndex % 128
            
            // Clear all notes at this step position
            cursorClip.clearStepsAtX(0, stepIndex)
            
            host.showPopupNotification("Cleared notes at beat ${String.format("%.3f", cursorPosition)}, step $stepIndex")
            
        } catch (e: Exception) {
            host.showPopupNotification("Error clearing notes: ${e.message}")
            host.println("Error details: ${e}")
        }
    }

    companion object {
        @JvmStatic
        fun createExtensionInstance(definition: ControllerExtensionDefinition, host: ControllerHost): ControllerExtension {
            return StepSequencerExtension(definition, host)
        }
    }
} 