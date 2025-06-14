package com.b3rnhard.steprecorder

import com.bitwig.extension.controller.api.Clip
import com.bitwig.extension.controller.api.ControllerHost
import kotlin.math.roundToInt

class Stepper(val host: ControllerHost) {

  private val minimumStepsAmount: Int = 128

  /**
   * 3: so that it is divisible by 3
   * 64: so that the smallest note length is still an integer value when integer based
   */
  private val integerBasedFactor = 64 * 3

  private val stepSize: Double = 1.0 / integerBasedFactor

  private var clip: Clip? = null

  fun initialize(clip: Clip) {
    this@Stepper.clip = clip
    clip.setStepSize(stepSize)
  }

  var cursorStep: Int = 0
    set(value) {
      field = value
      ensureWithinGridSize()
    }

  val clipGridWidth
    get() = this.minimumStepsAmount * integerBasedFactor

  // Default to 16th note
  private var integerBasedNoteLength: Int = (0.25 * integerBasedFactor).roundToInt()

  private fun integerBasedToBeats(integerValue: Int): Double {
    return integerValue.toDouble() / integerBasedFactor.toDouble();
  }

  private fun beatsToIntegerBased(beatsValue: Double): Int {
    return (beatsValue * integerBasedFactor).roundToInt()
  }

  private var scrollOffset: Int = 0

  private fun ensureWithinGridSize() {
    if (!(0 + scrollOffset..24000 + scrollOffset).contains(cursorStep)) {
      clip?.scrollToStep(cursorStep)
      scrollOffset = cursorStep
    }
  }

  val stepLengthInBeats: Double
    get() = integerBasedToBeats(integerBasedNoteLength)

  fun forward() {
    cursorStep = (cursorStep + integerBasedNoteLength)
    host.println("Cursor moved forward to: ${integerBasedToBeats(cursorStep)} beats")
  }

  fun backward() {
    cursorStep = (cursorStep - integerBasedNoteLength)
    host.println("Cursor moved backward to: ${integerBasedToBeats(cursorStep)} beats")
  }

  val x: Int
    get() {
      return cursorStep - scrollOffset
    }

  fun resetXFromBeats(beats: Double) {
    cursorStep = beatsToIntegerBased(beats)
  }

  fun forCursorStep(cursorStep: Int, action: (x: Int) -> Unit) {
    val oldCursorStep = cursorStep
    for (step in cursorStep..cursorStep + integerBasedNoteLength) {
      this.cursorStep = step
      action(this.x)
    }
    this.cursorStep = oldCursorStep
  }

  /**
   * Calculate note length in beats based on note value and triplet setting
   * Assumes 4/4 time signature where 1 whole note = 4 beats
   */
  fun updateNoteLengthInIntegerRepresentation(noteValue: String, noteType: String) {
    // Calculate base note length in beats (4/4 time signature)
    val baseLength: Int = beatsToIntegerBased(
      when (noteValue) {
        "32/1" -> 128.0  // 32 whole notes
        "16/1" -> 64.0  // 16 whole notes
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
    )
    // Apply triplet timing if selected (2/3 of regular note length)
    if (noteType != "Triplet") {
      integerBasedNoteLength = (baseLength)
    } else {
      integerBasedNoteLength = (baseLength * 2.0 / 3.0).toInt()
    }
    host.println("Note length: $stepLengthInBeats")
    host.showPopupNotification("Note Length: $noteValue $noteType")

  }
}