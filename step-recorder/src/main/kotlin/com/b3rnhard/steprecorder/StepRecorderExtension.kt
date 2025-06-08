package com.b3rnhard.steprecorder

import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.*

class StepRecorderExtension(definition: ControllerExtensionDefinition, host: ControllerHost) :
  ControllerExtension(definition, host) {

  private lateinit var documentState: DocumentState
  private lateinit var application: Application
  private lateinit var transport: Transport
  private lateinit var cursorTrack: CursorTrack
  private lateinit var clipLauncherCursorClip: Clip
  private lateinit var arrangerCursorClip: Clip
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
    documentState = host.documentState
    application = host.createApplication()
    transport = host.createTransport()
    preferences = host.preferences

    cursorTrack = host.createCursorTrack("steprecorder:CursorTrack", "Cursor Track", 0, 100, true)
    val trackBank = host.createTrackBank(100, 0, 100, false)
    trackBank.setShouldShowClipLauncherFeedback(true)
    stepper = Stepper(host)
    clipLauncherCursorClip = host.createLauncherCursorClip(stepper.clipGridWidth, 128)
    clipLauncherCursorClip.exists().markInterested()
    stepper.initialize(clipLauncherCursorClip)


    arrangerCursorClip = host.createArrangerCursorClip(stepper.clipGridWidth, 128)
    arrangerCursorClip.exists().markInterested()
//    stepper.setXFromBeats(1.0)

    clipLauncherCursorClip.playStart.markInterested()
    transport.playPosition().markInterested()
    transport.isPlaying().markInterested()

    setupEnable(trackBank)
    setupNoteType()
    setupBackward()
    setupForward()
    setupNoteValue()
    setupClear()
    setupClearToggle()
    setupNoteSelectionObserver()

    updateStepLength()
    resetCursorClipToPlayStart()

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
    tripletSetting.addValueObserver(this::updateStepLength.withArg())
    midiLearnBindings.add(
      MidiLearnBinding(
        host,
        "Toggle triplet",
        "MIDI Learn",
        "Not Mapped",
        {
          val current = tripletSetting.get()
          val newValue = if (current === "Regular") "Triplet" else "Regular"
          tripletSetting.set(newValue)
          updateStepLength()

        }
      )
    )
  }

  private fun setupForward() {
    cursorForwardAction = documentState.getSignalSetting("Cursor Forward", "Step Recorder", "Forward")

    val action = {
      if (clearToggleSetting.get()) {
        clearNotesAtCurrentStepRange()
      }
      updateCursorSelection(stepper.x)
      stepper.forward()
    }

    cursorForwardAction.addSignalObserver(action)
    midiLearnBindings.add(MidiLearnBinding(host, "Forward Button", "MIDI Learn", "Not Mapped", action.withArg()))
  }

  private fun setupBackward() {
    cursorBackwardAction = documentState.getSignalSetting("Cursor Backward", "Step Recorder", "Backward")

    val action = {
      stepper.backward()
      if (clearToggleSetting.get()) {
        clearNotesAtCurrentStepRange()
      }
      if (stepper.cursorStep != 0) {
        stepper.backward()
        updateCursorSelection(stepper.x)
        stepper.forward()
      }
    }

    cursorBackwardAction.addSignalObserver(action)
    midiLearnBindings.add(MidiLearnBinding(host, "Backward Button", "MIDI Learn", "Not Mapped", action.withArg()))
  }

  private lateinit var clearToggleSetting: SettableBooleanValue

  private fun setupClearToggle() {
    clearToggleSetting = documentState.getBooleanSetting("Clear toggle on forward/backward", "Step Recorder", true)

    val settingsAction = { value: Boolean ->
      host.showPopupNotification("Clear on forward/backward is now ${if (value) "enabled" else "disabled"}")
    }

    clearToggleSetting.addValueObserver(settingsAction)
    midiLearnBindings.add(
      MidiLearnBinding(
        host,
        "Clear Toggle on forward/backward Button",
        "MIDI Learn",
        "Not Mapped",
        { clearToggleSetting.set(!clearToggleSetting.get()) }
      )
    )
  }

  private fun setupClear() {
    val cursorClearSignal = documentState.getSignalSetting("Clear at Cursor", "Step Recorder", "Clear")


    cursorClearSignal.addSignalObserver(this::clearNotesAtCurrentStepRange)

    midiLearnBindings.add(
      MidiLearnBinding(
        host,
        "Clear Button",
        "MIDI Learn",
        "Not Mapped",
        this::clearNotesAtCurrentStepRange.withArg()
      )
    )
  }

  private val beatLengthTexts = arrayOf(
    "32/1", "16/1", "8/1", "4/1", "2/1", "1/1",
    "1/2", "1/4", "1/8", "1/16", "1/32", "1/64"
  )

  private fun setupNoteValue() {
    stepLengthValueSetting = documentState.getEnumSetting(
      "Step Length",
      "Step Recorder",
      beatLengthTexts,
      "1/4"
    )

    stepLengthValueSetting.addValueObserver { updateStepLength() }

    midiLearnBindings.add(
      MidiLearnBinding(
        host, "Step Length Value Control", "MIDI Learn", "Not Mapped",
        { changeStepLengthValue(it) })
    )
  }

  private var needsResetToPlayStart = true

  private fun setupEnable(trackBank: TrackBank) {
    val bankUtil = BankUtil(trackBank)

    bankUtil.forEachBank { bank, index ->
      bank.addIsSelectedObserver({ slotIndex: Int, isSelected: Boolean ->
        if (enableSetting.get()) {
          enableSetting.set(false)
          needsResetToPlayStart = true
          host.println("Step recorder is now disabled")
        }
      })
    }

    bankUtil.forEachClipLauncherSlot { slot, trackIndex, slotIndex ->
      slot.isSelected.addValueObserver { isSelected ->
        enableSetting.set(false)
        needsResetToPlayStart = true
      }
    }

    enableSetting = documentState.getBooleanSetting("Enable step recording", "Step Recorder", false)
    enableSetting.addValueObserver {
      if (it && needsResetToPlayStart) {
        needsResetToPlayStart = false
        resetCursorClipToPlayStart()
      }
    }

    midiLearnBindings.add(MidiLearnBinding(host, "Enable/Disable", "MIDI Learn", "Not Mapped") {
      val newEnabled = !enableSetting.get()
      enableSetting.set(newEnabled)
      val text = "Step recorder is now ${if (newEnabled) "enabled" else "disabled"}"
      host.showPopupNotification(text)
    })
  }

  private fun changeStepLengthValue(value: Int) {
    val noteLengthIndex = mapCCToNoteValueIndex(value) // Use the received MIDI value
    if (noteLengthIndex in beatLengthTexts.indices) {
      stepLengthValueSetting.set(beatLengthTexts[noteLengthIndex])
      host.showPopupNotification("Step Length: ${beatLengthTexts[noteLengthIndex]}")
    }
  }

  private fun setupNoteSelectionObserver() {
    clipLauncherCursorClip.addNoteStepObserver { isNoteStepOn ->
      if (!isUpdatingSelection && clipLauncherCursorClip.exists().get()) {
        updateCursorFromSelectedNotes()
      }
    }
  }

  private fun updateCursorSelection(beatTime: Int) {
    if (!clipLauncherCursorClip.exists().get()) return

    isUpdatingSelection = true
    try {
      clipLauncherCursorClip.selectStepContents(0, 0, 0, true)
      host.println("Select step $beatTime")
      for (key in 0..127) {
        clipLauncherCursorClip.selectStepContents(0, beatTime, key, false)
      }
    } catch (e: Exception) {
      host.println(e.stackTraceToString())
    } finally {
      isUpdatingSelection = false
    }
  }

  private fun updateCursorFromSelectedNotes() {
    // TODO
  }

  private fun processPendingNotes() {
    if (pendingNotes.isNotEmpty()) {
      val noteList = pendingNotes.sorted()

      if (noteList.size == 1) {
        addNotesToCurrentClip(noteList, 127)
      } else {
        val noteTimeSpan = lastNoteOnTime - firstNoteTime

        if (noteTimeSpan <= chordThresholdMs) {
          addNotesToCurrentClip(noteList, 127)
        } else {
          noteList.forEach { note ->
            addNotesToCurrentClip(listOf(note), 127)
          }
        }
      }

      pendingNotes.clear()
    }
  }

  private fun addNotesToCurrentClip(notes: List<Int>, velocity: Int) {
    if (!clipLauncherCursorClip.exists().get()) {
      host.showPopupNotification("No active clip found. Create or select a clip and open piano roll.")
      return
    }
    if (transport.isPlaying().get()) {
      host.println("Step recording disabled, transport is playing")
      return
    }

    val currentlyEnabled = enableSetting.get()
    if (!currentlyEnabled) {
      host.println("Step recording disabled")
      return
    }

    try {
      clearNotesAtCurrentStepRange()

      // Add all notes at the same position (chord)
      notes.forEach { note ->
        host.println("setStep y:${note}, x:${stepper.x}")
        // - 0.0001 prevents note lengths too close to the next and clearing deletes both
        val nl = stepper.stepLengthInBeats - 0.0001
        clipLauncherCursorClip.setStep(0, stepper.x, note, velocity, nl)
      }

      val noteNames = notes.joinToString(", ") { getNoteNameFromMidi(it) }
      host.println("Added $noteNames at step ${stepper.x}")

      updateCursorSelection(stepper.x)
      stepper.forward()

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
    host.println("Step Recorder Exited")
  }

  override fun flush() {
    // Nothing specific to flush
  }

  private fun resetCursorClipToPlayStart() {
    val playStartValue = clipLauncherCursorClip.playStart.get()
    val newStep = stepper.resetXFromBeats(playStartValue, clipLauncherCursorClip)

    host.println(
      "Reset step recorder cursor to play start ${
        host.defaultBeatTimeFormatter().formatBeatTime(playStartValue, true, 4, 4, 100)
      }"
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

  private fun clearNotesAtCurrentStepRange() {
    val range = stepper.rangeOfXForCurrentStep
    host.println("Clear step range ${range.first}..${range.last}")
    range.forEach {
      clipLauncherCursorClip.clearStepsAtX(0, it)
    }
  }

  companion object {
    @JvmStatic
    fun createExtensionInstance(
      definition: ControllerExtensionDefinition,
      host: ControllerHost
    ): ControllerExtension {
      return StepRecorderExtension(definition, host)
    }
  }
}