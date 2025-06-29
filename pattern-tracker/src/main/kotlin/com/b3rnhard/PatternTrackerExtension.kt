package com.b3rnhard

import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.versionFromProperties
import com.b3rnhard.sharedcomponents.ISettableBooleanValue
import com.b3rnhard.sharedcomponents.getEnumBasedBooleanSetting
import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.api.*

fun SettableRangedValue.intValue(): Int = this.get().toInt()

data class Observation(
  val tracksPerGroupAmount: SettableRangedValue,
  val rootTracksAmount: SettableRangedValue,
  val slotsPerTrackAmount: SettableRangedValue
)

data class Settings(
  val observation: Observation,
  val stopKeyword: SettableStringValue,
  val keepDevicesPlaying: ISettableBooleanValue,
  val remapSignal: Unit
)

data class DeviceSlotInfo(val clipLauncherSlot: ClipLauncherSlot, val track: Track)

data class FireSlotState(
  var name: String = "",
  var isPlaying: Boolean? = null,
)

class PatternTrackerExtension(definition: PatternTrackerExtensionDefinition, host: ControllerHost) :
  ControllerExtension(definition, host) {
  private var impl: PatternTrackerExtensionImplementation? = null

  override fun init() {
    impl = PatternTrackerExtensionImplementation(host)
  }

  override fun exit() {
    impl?.exit()
  }

  override fun flush() {}
}

class PatternTrackerExtensionImplementation(val host: ControllerHost) {
  private val logger: Logger = Logger(host, { Severity.warning })
  private val devicesGroupName = "Devices"
  private val firePatternGroupName = "Patterns"
  private val settings: Settings = setupSettings()

  private val transport = host.createTransport()

  private val deviceSlotClipMap: MutableMap<String, DeviceSlotInfo> = mutableMapOf()
  private val firePatternSlotClipState: MutableMap<Int, MutableMap<Int, MutableMap<Int, FireSlotState>>> =
    mutableMapOf()
  private val topLevelTracks: MutableMap<Int, Track> = mutableMapOf()
  private val flattenedTracksByGroup: MutableMap<Int, MutableMap<Int, Track>> = mutableMapOf()
  private val slotsOfFlattenedTracksByGroup: MutableMap<Int, MutableMap<Int, MutableMap<Int, ClipLauncherSlot>>> =
    mutableMapOf()

  init {
    logger.logMessage("=== Pattern Tracker Extension v$versionFromProperties Starting ===", Severity.info)

    tryCatch({
      setupTransportStateChangedHandler()

      val settings = setupSettings()
      val project = host.project
      val rootTrackGroup = project.rootTrackGroup

      val rootTrackBank = rootTrackGroup.createTrackBank(settings.observation.rootTracksAmount.intValue(), 0, 0, false)
      rootTrackBank.scrollPosition().set(0)
      rootTrackBank.setShouldShowClipLauncherFeedback(true)

      for (topLevelTrackIndex in 0 until rootTrackBank.sizeOfBank) {
        val topLevelTrack = rootTrackBank.getItemAt(topLevelTrackIndex)

        topLevelTracks[topLevelTrackIndex] = topLevelTrack
        var currentChildTrackMap = flattenedTracksByGroup.getOrPut(topLevelTrackIndex) { mutableMapOf() }
        val currentChildSlotMap = slotsOfFlattenedTracksByGroup.getOrPut(topLevelTrackIndex) { mutableMapOf() }

        topLevelTrack.exists().markInterested()
        topLevelTrack.name().markInterested()
        topLevelTrack.isGroup().markInterested()

        tryCatch({
          val flattenedTracksGroup = topLevelTrack.createTrackBank(
            settings.observation.tracksPerGroupAmount.intValue(),
            0,
            settings.observation.slotsPerTrackAmount.intValue(),
            true
          )
          flattenedTracksGroup.scrollPosition().set(0)
          flattenedTracksGroup.setShouldShowClipLauncherFeedback(true)

          for (trackIndexOfFlattenedTracksGroup in 0 until flattenedTracksGroup.sizeOfBank) {
            val trackOfFlattenedTracksGroup = flattenedTracksGroup.getItemAt(trackIndexOfFlattenedTracksGroup)
            currentChildTrackMap[trackIndexOfFlattenedTracksGroup] = trackOfFlattenedTracksGroup
            val currentSlotMap = currentChildSlotMap.getOrPut(trackIndexOfFlattenedTracksGroup) { mutableMapOf() }

            with(trackOfFlattenedTracksGroup) {
              exists().markInterested()
              name().markInterested()
              isGroup().markInterested()
            }

            val slotsOfChildTrack = trackOfFlattenedTracksGroup.clipLauncherSlotBank()
            slotsOfChildTrack.scrollPosition().set(0)

            for (slotIndex in 0 until slotsOfChildTrack.sizeOfBank) {
              val slot = slotsOfChildTrack.getItemAt(slotIndex)
              currentSlotMap[slotIndex] = slot

              with(slot) {
                exists().markInterested()
                name().markInterested()
                hasContent().markInterested()
                isPlaying().markInterested()
                isPlaybackQueued().markInterested()

                fun isSlotInGroup(group: String): Boolean =
                  topLevelTrack.exists().get() && trackOfFlattenedTracksGroup.exists().get() && topLevelTrack.name()
                    .get() == group && topLevelTrack.isGroup()
                    .get() && !trackOfFlattenedTracksGroup.isGroup()
                    .get()

                name().addValueObserver { name ->
                  if (isSlotInGroup(devicesGroupName)) {
                    setupDeviceSlotMapping(name, hasContent().get(), slot, trackOfFlattenedTracksGroup, slotIndex)
                  }
                }

                hasContent().addValueObserver { hasContent ->
                  if (isSlotInGroup(devicesGroupName)) {
                    setupDeviceSlotMapping(name().get(), hasContent, slot, trackOfFlattenedTracksGroup, slotIndex)
                  }
                }

                val getSlotState = {
                  firePatternSlotClipState
                    .getOrPut(topLevelTrackIndex) { mutableMapOf() }
                    .getOrPut(trackIndexOfFlattenedTracksGroup) { mutableMapOf() }
                    .getOrPut(slotIndex) { FireSlotState() }
                }

                name().addValueObserver { name ->
                  if (isSlotInGroup(firePatternGroupName)) {
                    val patternSlotState = getSlotState()
                    setupFirePatternSlotNameChangedHandler(
                      patternSlotState,
                      name,
                      topLevelTrackIndex,
                      trackIndexOfFlattenedTracksGroup,
                      slotIndex
                    )
                  }
                }

                isPlaying().addValueObserver { isPlaying ->
                  if (isSlotInGroup(firePatternGroupName)
                  ) {
                    val patternSlotState = getSlotState()
                    handlePatternSlotPlayingStateChanged(
                      patternSlotState,
                      isPlaying
                    )
                  }
                }
              }
            }
          }
        }) {
          "Error during init observer/reference setup for top-level track $topLevelTrackIndex: ${it.message}" to Unit
        }
      }

      logStoredReferences()
    }) { "Error during initialization: ${it.message}" to Unit }
  }

  private fun handleError(exception: Exception, message: String) {
    logger.logMessage(message, Severity.error)
    host.showPopupNotification(message)
    host.println(message)
    host.println(exception.stackTraceToString())
  }

  private fun <TReturn> tryCatch(
    processor: () -> TReturn,
    getMessage: (e: Exception) -> Pair<String, TReturn>
  ): TReturn {
    try {
      return processor()
    } catch (e: Exception) {
      val (message, result) = getMessage(e)
      handleError(e, message)
      return result
    }
  }

  private fun findAndLaunchDeviceClip(fireClipName: String): ClipLauncherSlot? {
    if (fireClipName.isEmpty()) return null
    val deviceSlotInfo = deviceSlotClipMap[fireClipName]
    if (deviceSlotInfo == null) {
      logger.logMessage("Device clip named \"$fireClipName\" not found.", Severity.warning)
      return null
    }

    return tryCatch({
      if (deviceSlotInfo.track.exists().get()) {
        deviceSlotInfo.clipLauncherSlot.launchWithOptions("none", "default")
        return@tryCatch deviceSlotInfo.clipLauncherSlot
      }
      logger.logMessage(
        "Device track for clip \"$fireClipName\" no longer exists. Removing mapping.",
        Severity.info
      )
      deviceSlotClipMap.remove(fireClipName)
      return@tryCatch null
    }) { "Error during launch check/action for $fireClipName: ${it.message}" to null }
  }

  private fun findAndStopDeviceClip(firePatternClipName: String) {
    if (firePatternClipName.isEmpty()) return
    val deviceSlotInfo = deviceSlotClipMap[firePatternClipName]
    if (deviceSlotInfo == null) {
      logger.logMessage("Device clip named \"$firePatternClipName\" not found.", Severity.warning)
      return
    }
    tryCatch({
      if (deviceSlotInfo.track.exists().get()) {
        deviceSlotInfo.track.stop()
      } else {
        logger.logMessage(
          "Could not find track to stop for device clip \"$firePatternClipName\" (or track doesn't exist).",
          Severity.warning
        )
      }
    }) { "Error during stop check/action for $firePatternClipName: ${it.message}" to Unit }
  }

  private fun handleStopCommand(stopPrefixedClipTrackName: String): Boolean {
    val stopKeyword = settings.stopKeyword.get()

    if (stopKeyword.isNullOrBlank() || !stopPrefixedClipTrackName.startsWith(stopKeyword)) {
      return false
    }

    val trackName = stopPrefixedClipTrackName.substring(stopKeyword.length).trim()
    if (trackName.isNotEmpty()) {
      val deviceInfo = deviceSlotClipMap.values.find { it.track.name().get() == trackName }
      if (deviceInfo != null) {
        deviceInfo.track.stop()
      } else {
        logger.logMessage("Could not find device track named \"$trackName\".", Severity.warning)
      }
    }
    return true
  }

  fun processGroupChildren(
    index: Int,
    slotAction: (childTrack: Track, childIndex: Int, slot: ClipLauncherSlot, slotIndex: Int) -> Unit
  ) {
    val tracksOfGroup = flattenedTracksByGroup[index]
    val slotsOfGroup = slotsOfFlattenedTracksByGroup[index]
    if (tracksOfGroup == null || slotsOfGroup == null) {
      return
    }
    tracksOfGroup.forEach groupChild@{ (childIndex, childTrack) ->
      if (!childTrack.exists().get() || childTrack.isGroup().get()) {
        return@groupChild
      }
      val slotsOfChild = slotsOfGroup[childIndex]
      if (slotsOfChild == null) {
        return@groupChild
      }
      slotsOfChild.forEach groupSlot@{ (slotIndex, slot) ->
        slotAction(childTrack, childIndex, slot, slotIndex)
      }
    }
  }

  private fun remapClips() {
    logger.logMessage("--- Starting Manual Clip Remapping ---", Severity.trace)
    deviceSlotClipMap.clear()
    firePatternSlotClipState.values.forEach { parentMap ->
      parentMap.values.forEach { childMap ->
        childMap.values.forEach { slotState ->
          slotState.name = ""
        }
      }
    }
    val resultMessageLines = mutableListOf<String>()

    var deviceGroupFound = false
    var patternGroupFound = false

    tryCatch({
      topLevelTracks.forEach { (topLevelTrackIndex, track) ->
        if (!track.exists().get()) {
          return@forEach
        }
        val trackName = track.name().get()
        val isGroup = track.isGroup().get()
        if (isGroup && trackName == devicesGroupName) {
          deviceGroupFound = true
          processGroupChildren(
            topLevelTrackIndex
          ) { childTrack, _, slot, slotIndex ->
            val clipName = slot.name().get()
            val hasContent = slot.hasContent().get()
            if (hasContent && clipName.isNotEmpty()) {
              if (deviceSlotClipMap.containsKey(clipName)) {
                resultMessageLines += "Duplicate device clip name found: $clipName on track $trackName"
              }
              deviceSlotClipMap[clipName] = DeviceSlotInfo(slot, childTrack)
            }
          }
        } else if (isGroup && trackName == firePatternGroupName) {
          patternGroupFound = true
          val parentStateMap = firePatternSlotClipState.getOrPut(topLevelTrackIndex) { mutableMapOf() }
          processGroupChildren(
            topLevelTrackIndex
          ) { _, childIndex, slot, slotIndex ->
            val slotName = slot.name().get()
            val patternTrackStateMap = parentStateMap.getOrPut(childIndex) { mutableMapOf() }
            val patternSlotState = patternTrackStateMap.getOrPut(slotIndex) { FireSlotState() }
            if (patternSlotState.name != slotName) {
              patternSlotState.name = slotName
            }
            if (patternSlotState.name.isNotEmpty() && patternSlotState.isPlaying == true && deviceSlotClipMap.containsKey(
                patternSlotState.name
              )
            ) {
              findAndLaunchDeviceClip(patternSlotState.name)
            }
          }
        }
      }
    }) {
      val errorMessage = "Error during manual clip remapping: ${it.message} (see console log)"
      resultMessageLines += errorMessage
      errorMessage to Unit
    }
    if (!deviceGroupFound) {
      resultMessageLines += "\"$devicesGroupName\" group track not found during remap."
    }
    if (!patternGroupFound) {
      resultMessageLines += "\"$firePatternGroupName\" group track not found during remap."
    }
    if (resultMessageLines.isNotEmpty()) {
      logger.logMessage(resultMessageLines.joinToString("\n"), Severity.error)
    }
    logger.logMessage("--- Pattern Slot Names After Remap ---", Severity.info)
    logNestedMapSummary(host, firePatternSlotClipState, "Fire Slots State Structure")
  }

  private fun setupSettings(): Settings {
    return Settings(
      setupObservationWindowSettings(),
      setupStopKeywordSetting(),
      setupKeepDevicesPlayingSetting(),
      setupRemapSetting()
    )
  }

  private fun setupObservationWindowSettings(): Observation {
    val tracksPerGroupAmountSetting = host.preferences.getNumberSetting(
      "Number of Tracks per Group",
      "Tracks observation window",
      1.0,
      200.0,
      1.0,
      "",
      25.0
    )
    val rootTracksAmountSetting = host.preferences.getNumberSetting(
      "Number of root Groups",
      "Tracks observation window",
      1.0,
      200.0,
      1.0,
      "",
      25.0
    )
    val slotsAmountPerTrackSetting = host.preferences.getNumberSetting(
      "Number of Slots per Track",
      "Slots observation window",
      1.0,
      500.0,
      1.0,
      "",
      25.0
    )

    fun addObserverForSetting(name: String, setting: SettableRangedValue) {
      setting.addRawValueObserver { value: Double ->
        logger.logMessage(
          "Number of $name changed to ${value.toInt()}. " +
                  "Please restart the extension for changes to take effect.", Severity.info
        )
      }
    }
    addObserverForSetting("tracks", tracksPerGroupAmountSetting!!)
    addObserverForSetting("slots", slotsAmountPerTrackSetting!!)
    addObserverForSetting("root groups", rootTracksAmountSetting!!)
    return Observation(tracksPerGroupAmountSetting, rootTracksAmountSetting, slotsAmountPerTrackSetting)
  }

  private fun setupRemapSetting() {
    val remapAction = host.documentState.getSignalSetting(
      "Mapping",
      "Remap Clips",
      "Scan tracks and map clips by name"
    )
    remapAction.addSignalObserver(::remapClips)
  }

  private fun setupStopKeywordSetting(): SettableStringValue {
    return host.preferences.getStringSetting(
      "Pattern",
      "Stop Keyword",
      1024,
      "[stop]"
    )
  }

  private fun setupKeepDevicesPlayingSetting(): ISettableBooleanValue {
    val setting = host.documentState.getEnumBasedBooleanSetting(
      "Keep Devices Playing on Pattern Stop",
      "Mapping",
      false
    )

    fun booleanToString(value: Boolean): String = if (value) "enabled" else "disabled"

    setting.addValueObserver { value ->
      logger.logMessage("Keep Devices Playing: ${booleanToString(value)}", Severity.info)
    }
    return setting
  }

  fun logStoredReferences() {
    host.println("--- Stored References ---")
    host.println("Top Level Tracks (${topLevelTracks.size}):")
    topLevelTracks.forEach { (idx: Int, track: Track) ->
      tryCatch({
        host.println(
          "  [$idx]: Name=\"${track.name().get()}\", IsGroup=${
            track.isGroup().get()
          }, Exists=${track.exists().get()}"
        )
      }) {
        "[$idx]: Error getting info: ${it.message}" to Unit
      }
    }
    flattenedTracksByGroup.forEach { (parentIdx: Int, childMap: MutableMap<Int, Track>) ->
      host.println("  Group $parentIdx (${childMap.size} children):")
      childMap.forEach { (childIdx: Int, childTrack: Track) ->
        tryCatch({
          host.println(
            "    [$parentIdx, $childIdx]: Name=\"${childTrack.name().get()}\", IsGroup=${
              childTrack.isGroup().get()
            }, Exists=${childTrack.exists().get()}"
          )
        }) { "[$parentIdx, $childIdx]: Error getting info: ${it.message}" to Unit }
      }
    }
    logNestedMapSummary(host, slotsOfFlattenedTracksByGroup, "  Child Slots Structure")
  }

  private fun setupDeviceSlotMapping(
    currentSlotName: String,
    currentHasContent: Boolean,
    slot: ClipLauncherSlot,
    track: Track,
    slotIndex: Int
  ) {
    tryCatch({
      val currentMappingEntry = deviceSlotClipMap.entries.find { it.value.clipLauncherSlot == slot }

      if (currentMappingEntry != null && (currentMappingEntry.key != currentSlotName || !currentHasContent || currentSlotName.isEmpty())) {
        deviceSlotClipMap.remove(currentMappingEntry.key)
      }

      if (currentHasContent && currentSlotName.isNotEmpty()) {
        val existingMappingForName = deviceSlotClipMap[currentSlotName]
        if (existingMappingForName == null || existingMappingForName.clipLauncherSlot != slot) {
          if (existingMappingForName != null) {
            host.println("    -> Name conflict: Unmapping old clip for \"$currentSlotName\"")
          }
          host.println("    -> Mapping device clip: \"$currentSlotName\"")
          deviceSlotClipMap[currentSlotName] = DeviceSlotInfo(slot, track)
        }
      }
    }) {
      "Error in setupDeviceSlotMapping for slot $slotIndex: ${it.message}" to Unit
    }
  }

  private fun handleLaunchCommand(deviceClipName: String) {
    if (!transport.isPlaying.get()) {
      return
    }
    findAndLaunchDeviceClip(deviceClipName)
  }

  private fun setupTransportStateChangedHandler() {
    fun handleTransportStateChange(transportIsPlaying: Boolean) {
      firePatternSlotClipState.forEach { (_, parentMap) ->
        parentMap.forEach { (_, childMap) ->
          childMap.forEach { (_, slotState) ->
            if (slotState.isPlaying == true && slotState.name.isNotEmpty() && slotState.name != settings.stopKeyword.get()) {
              if (transportIsPlaying) {
                findAndLaunchDeviceClip(slotState.name)
              } else {
                findAndStopDeviceClip(slotState.name)
              }
            }
          }
        }
      }
    }

    transport.isPlaying().markInterested()
    transport.isPlaying().addValueObserver { playing ->
      handleTransportStateChange(playing)
    }
  }

  private fun setupFirePatternSlotNameChangedHandler(
    slotState: FireSlotState,
    name: String,
    parentTrackIndex: Int,
    childTrackIndex: Int,
    slotIndex: Int
  ) {
    tryCatch({
      val oldName = slotState.name
      slotState.name = name

      logger.logMessage(
        "Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] name changed from \"${oldName.ifEmpty { "<init>" }}\" to \"${name.ifEmpty { "<empty>" }}\"",
        Severity.trace
      )

      if (name.isEmpty() || slotState.isPlaying != true) {
        return@tryCatch
      }

      if (handleStopCommand(name)) {
        return@tryCatch
      }

      handleLaunchCommand(name)

    }) {
      "Error in setupFirePatternSlotNameChangedHandler: ${it.message}" to Unit
    }
  }

  private fun handlePatternSlotPlayingStateChanged(
    slotState: FireSlotState,
    isPlaying: Boolean
  ) {
    tryCatch({
      val isTransportPlaying = transport.isPlaying.get()
      if (slotState.isPlaying == null) {
        slotState.isPlaying = isPlaying
      }
      if (isPlaying == slotState.isPlaying) return@tryCatch

      // isPlaying changed
      slotState.isPlaying = isPlaying
      val currentName = slotState.name

      if (isPlaying) {
        if (isTransportPlaying) {
          findAndLaunchDeviceClip(slotState.name)
        }
      } else {
        if (currentName.startsWith(settings.stopKeyword.get())) {
          return@tryCatch
        }

        if (!settings.keepDevicesPlaying.get()) {
          findAndStopDeviceClip(slotState.name)
        }
      }
    }) {
      "Error in fire slot isPlaying logic: ${it.message}" to Unit
    }
  }

  fun exit() {
    logger.logMessage("=== Pattern Tracker Extension v$versionFromProperties Exited ===", Severity.info)
  }
}