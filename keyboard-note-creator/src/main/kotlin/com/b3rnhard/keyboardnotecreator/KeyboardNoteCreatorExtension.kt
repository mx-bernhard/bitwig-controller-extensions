package com.b3rnhard.keyboardnotecreator

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

class KeyboardNoteCreatorExtension(definition: ControllerExtensionDefinition, host: ControllerHost) : ControllerExtension(definition, host) {

    private lateinit var documentState: DocumentState
    private lateinit var application: Application
    private lateinit var cursorTrack: CursorTrack
    private lateinit var cursorClip: Clip
    private lateinit var transport: Transport
    private lateinit var midiIn: MidiIn
    private lateinit var noteInput: NoteInput
    private lateinit var noteLengthSetting: SettableRangedValue
    private lateinit var resetCursorAction: Signal
    private lateinit var cursorForwardAction: Signal
    private lateinit var cursorBackwardAction: Signal

    private var noteLength: Double = 0.25 // Default value, will be updated by the setting
    private var cursorPosition: Double = 0.0 // Our own cursor position in beats
    private var isUpdatingSelection: Boolean = false // Flag to prevent infinite recursion
    
    // Chord detection state
    private val activeNotes = mutableMapOf<Int, Long>() // MIDI note -> timestamp
    private val pendingNotes = mutableSetOf<Int>() // Notes waiting to be placed
    private var firstNoteTime: Long = 0
    private var lastNoteOnTime: Long = 0
    private val chordTimeoutMs: Long = 100 // Maximum time span between first and last note to consider them a chord

    override fun init() {
        // Get document state and application
        documentState = host.documentState
        application = host.createApplication()
        transport = host.createTransport()

        // Create cursor track that follows selection
        cursorTrack = host.createCursorTrack("KeyboardNoteCreator:CursorTrack", "Cursor Track", 0, 0, true)

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
        noteLengthSetting = documentState.getNumberSetting(
            "Note Length", // name
            "Keyboard Note Creator", // category  
            0.01, // minimum value
            4.0, // maximum value (whole note)
            0.01, // step size
            " Beat", // display unit
            0.25 // initial value (16th note)
        )

        // Add observer to update noteLength when the setting changes
        noteLengthSetting.addValueObserver { newValue ->
            noteLength = newValue
            host.showPopupNotification("Note Length: ${String.format("%.3f", noteLength)} beats")
        }

        // Initialize noteLength with current setting value
        noteLength = noteLengthSetting.get()

        // Initialize cursor position to current clip start position if clip exists, otherwise transport position
        if (cursorClip.exists().get()) {
            cursorPosition = cursorClip.getPlayStart().get()
        } else {
            cursorPosition = transport.playPosition().get()
        }

        // Add a button to reset cursor position to current transport position
        resetCursorAction = documentState.getSignalSetting(
            "Reset Cursor", // name
            "Keyboard Note Creator", // category
            "Reset Cursor Position" // label
        )
        
        resetCursorAction.addSignalObserver {
            // Reset to beginning of clip if clip exists, otherwise to transport position
            if (cursorClip.exists().get()) {
                cursorPosition = cursorClip.getPlayStart().get()
                host.showPopupNotification("Cursor reset to clip start: ${String.format("%.3f", cursorPosition)} beats")
            } else {
                cursorPosition = transport.playPosition().get()
                host.showPopupNotification("Cursor reset to transport position: ${String.format("%.3f", cursorPosition)} beats")
            }
            updateCursorSelection()
        }

        // Add cursor navigation commands
        cursorForwardAction = documentState.getSignalSetting(
            "Cursor Forward", // name
            "Keyboard Note Creator", // category
            "Move Cursor Forward" // label
        )
        
        cursorForwardAction.addSignalObserver {
            cursorPosition += noteLength
            host.showPopupNotification("Cursor moved forward to: ${String.format("%.3f", cursorPosition)} beats")
            updateCursorSelection()
        }

        cursorBackwardAction = documentState.getSignalSetting(
            "Cursor Backward", // name
            "Keyboard Note Creator", // category
            "Move Cursor Backward" // label
        )
        
        cursorBackwardAction.addSignalObserver {
            cursorPosition = (cursorPosition - noteLength).coerceAtLeast(0.0)
            host.showPopupNotification("Cursor moved backward to: ${String.format("%.3f", cursorPosition)} beats")
            updateCursorSelection()
        }

        // Get MIDI input port and set up input/callback
        midiIn = host.getMidiInPort(0)
        noteInput = midiIn.createNoteInput("Keyboard", "80????", "90????")
        noteInput.setShouldConsumeEvents(false) // Don't consume events so they can still trigger notes in the DAW

        // Set up a MIDI callback to handle note on/off for chord detection
        midiIn.setMidiCallback { status: Int, data1: Int, data2: Int ->
            val currentTime = System.currentTimeMillis()
            
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

        host.showPopupNotification("Keyboard Note Creator Initialized")
        host.showPopupNotification("Double-click an arranger clip to open piano roll, then play notes!")
        
        // Set up note selection observer to track selected notes and update cursor position
        setupNoteSelectionObserver()
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
                
                if (noteTimeSpan <= chordTimeoutMs) {
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
        host.showPopupNotification("Keyboard Note Creator Exited")
    }

    override fun flush() {
        // Nothing specific to flush
    }

    companion object {
        @JvmStatic
        fun createExtensionInstance(definition: ControllerExtensionDefinition, host: ControllerHost): ControllerExtension {
            return KeyboardNoteCreatorExtension(definition, host)
        }
    }
} 