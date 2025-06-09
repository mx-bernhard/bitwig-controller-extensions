package com.b3rnhard.steprecorder

import com.bitwig.extension.api.PlatformType
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList
import com.bitwig.extension.controller.ControllerExtensionDefinition
import com.bitwig.extension.controller.api.ControllerHost
import java.util.*

class StepRecorderExtensionDefinition : ControllerExtensionDefinition() {
  override fun getName(): String = "Step Recorder"
  override fun getAuthor(): String = "b3rnhard"

  override fun getVersion(): String {
    return versionFromProperties
  }

  override fun getId(): UUID = UUID.fromString("6c0e6f1b-4b7a-4c8a-8f1a-5b9a9c3d5a7e")
  override fun getHardwareVendor(): String = "Generic"
  override fun getHardwareModel(): String = "Step Recorder"
  override fun getRequiredAPIVersion(): Int = 22

  override fun getNumMidiInPorts(): Int = 1 // Now we need one MIDI input from virtual port
  override fun getNumMidiOutPorts(): Int = 0

  override fun listAutoDetectionMidiPortNames(
    list: AutoDetectionMidiPortNamesList,
    platformType: PlatformType
  ) {
    list.add(arrayOf("Step Recorder"), arrayOfNulls(0))
  }

  override fun createInstance(host: ControllerHost): StepRecorderExtension {
    return StepRecorderExtension(this, host)
  }

  companion object {
    val versionFromProperties: String by lazy {
      try {
        val props = Properties()
        StepRecorderExtensionDefinition::class.java.getResourceAsStream("/version.properties").use {
          props.load(it)
        }
        props.getProperty("version", "0.0.0-dev")
      } catch (e: Exception) {
        "0.0.0-error"
      }
    }
  }

}