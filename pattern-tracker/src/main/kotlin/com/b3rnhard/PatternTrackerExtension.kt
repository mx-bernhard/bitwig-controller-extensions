package com.b3rnhard

import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.versionFromProperties
import com.b3rnhard.sharedcomponents.ISettableBooleanValue
import com.b3rnhard.sharedcomponents.getEnumBasedBooleanSetting

import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.api.ClipLauncherSlot
import com.bitwig.extension.controller.api.ControllerHost
import com.bitwig.extension.controller.api.SettableRangedValue
import com.bitwig.extension.controller.api.Track

// --- Helper Data Classes (Moved outside the main class) ---
data class DeviceSlotInfo(val slot: ClipLauncherSlot, val track: Track)
data class FireSlotState(
  var name: String = "",
  var isPlaying: Boolean? = null,
  var deviceSlot: ClipLauncherSlot? = null,
)

// --- Type Aliases ---
// Map: parentTrackIndex -> childTrackIndex -> Value
typealias TrackSlotMap<T> = MutableMap<Int, MutableMap<Int, T>>
// Map: parentTrackIndex -> childTrackIndex -> slotIndex -> Value
typealias TrackSlotItemMap<T> = MutableMap<Int, MutableMap<Int, MutableMap<Int, T>>>

class PatternTrackerExtension(definition: PatternTrackerExtensionDefinition, host: ControllerHost) :
  ControllerExtension(definition, host) {
  private val devicesGroupName = "Devices"
  private val firePatternGroupName = "Patterns"

  // --- State ---
  private val deviceClipMap: MutableMap<String, DeviceSlotInfo> = mutableMapOf()
  private val fireSlotsState: TrackSlotItemMap<FireSlotState> = mutableMapOf() // Corrected Type

  // Storing references obtained during init
  private val topLevelTracks: MutableMap<Int, Track> = mutableMapOf()
  private val childTracks: TrackSlotMap<Track> = mutableMapOf()

  // Corrected Type: Needs to store ClipLauncherSlot, not FireSlotState
  private val childSlots: TrackSlotItemMap<ClipLauncherSlot> = mutableMapOf()

  // Remove MAX_TRACKS and MAX_SLOTS usage, make them configurable
  private var numTracks: Int = 2
  private var numRootTracks: Int = 2
  private var numSlots: Int = 2
  private var stopKeyword: String = "[stop]"

  // --- Transport State ---
  private var isTransportPlaying: Boolean = false

  private var keepDevicesPlayingSetting: ISettableBooleanValue? = null

  // --- Helper Functions ---

  private fun findAndLaunchDeviceClip(fireClipName: String): ClipLauncherSlot? {
    val deviceInfo = deviceClipMap[fireClipName]
    if (deviceInfo != null) {
      try {
        if (!deviceInfo.track.exists().get()) {
          host.println("Warning: Device track for clip \"$fireClipName\" no longer exists. Removing mapping.")
          deviceClipMap.remove(fireClipName)
          return null
        }
        host.println(
          "Launching device clip \"$fireClipName\" on track \"${
            deviceInfo.track.name().get()
          }\" with no quantization."
        )
        deviceInfo.slot.launchWithOptions("none", "default")
        return deviceInfo.slot
      } catch (e: Exception) {
        host.println("Error during launch check/action for $fireClipName: ${e.message}")
        return null
      }
    } else {
      host.println("Warning: Device clip named \"$fireClipName\" not found.")
      return null
    }
  }

  private fun findAndStopDeviceClip(fireClipName: String) {
    val deviceInfo = deviceClipMap[fireClipName]
    try {
      if (deviceInfo?.track?.exists()?.get() == true) {
        host.println(
          "Stopping playback on device track \"${
            deviceInfo.track.name().get()
          }\" for clip \"$fireClipName\""
        )
        deviceInfo.track.stop()
      } else {
        host.println("Warning: Could not find track to stop for device clip \"$fireClipName\" (or track doesn't exist).")
        if (deviceInfo != null) {
          deviceClipMap.remove(fireClipName)
        }
      }
    } catch (e: Exception) {
      host.println("Error during stop check/action for $fireClipName: ${e.message}")
    }
  }

  // Helper to process group children and slots
  private fun processGroupChildren(
    groupName: String,
    groupIndex: Int,
    childrenOfGroup: Map<Int, Track>?,
    slotsOfGroup: Map<Int, Map<Int, ClipLauncherSlot>>?,
    slotLogic: (childTrack: Track, childIndex: Int, slot: ClipLauncherSlot, slotIndex: Int) -> Unit
  ) {
    if (childrenOfGroup == null || slotsOfGroup == null) {
      host.println("    WARN: No stored child tracks or slots found for $groupName group index $groupIndex")
      return
    }
    childrenOfGroup.forEach groupChild@{ (childIndex, childTrack) ->
      if (!childTrack.exists().get() || childTrack.isGroup().get()) {
        host.println("    Skipping non-existent or nested group child track index $childIndex")
        return@groupChild
      }
      val childTrackName = childTrack.name().get()
      host.println(
        "    $groupName Child Track $childIndex: Name=\"$childTrackName\" (Exists: ${
          childTrack.exists().get()
        })"
      )
      val slotsOfChild = slotsOfGroup[childIndex]
      if (slotsOfChild == null) {
        host.println("      WARN: No stored slots found for $groupName child track index $childIndex")
        return@groupChild
      }
      slotsOfChild.forEach groupSlot@{ (slotIndex, slot) ->
        if (!slot.exists().get()) {
          host.println("        Skipping non-existent slot index $slotIndex")
          return@groupSlot
        }
        slotLogic(childTrack, childIndex, slot, slotIndex)
      }
    }
  }

  private fun remapClips() {
    host.println("--- Starting Manual Clip Remapping ---")
    deviceClipMap.clear()
    fireSlotsState.values.forEach { parentMap ->
      parentMap.values.forEach { childMap ->
        childMap.values.forEach { slotState ->
          slotState.name = ""
          slotState.deviceSlot = null
        }
      }
    }

    var deviceGroupFound = false
    var patternGroupFound = false

    try {
      host.println("Remap: Iterating through ${topLevelTracks.size} stored top-level tracks.")
      topLevelTracks.forEach { (trackIndex, track) ->
        if (!track.exists().get()) {
          host.println("Remap: Skipping non-existent top-level track index $trackIndex")
          return@forEach
        }
        val trackName = track.name().get()
        val isGroup = track.isGroup().get()
        host.println("Remap: Processing stored top-level track $trackIndex: Name=\"$trackName\", IsGroup=$isGroup")
        if (isGroup && trackName == devicesGroupName) {
          deviceGroupFound = true
          host.println("  -> Found DEVICES group (Index $trackIndex). Processing stored children.")
          processGroupChildren(
            devicesGroupName,
            trackIndex,
            childTracks[trackIndex],
            childSlots[trackIndex]
          ) { childTrack, _, slot, slotIndex ->
            val slotName = slot.name().get()
            val hasContent = slot.hasContent().get()
            host.println(
              "      Slot $slotIndex: Name=\"$slotName\", HasContent=$hasContent (Exists: ${
                slot.exists().get()
              })"
            )
            if (hasContent && slotName.isNotEmpty()) {
              if (deviceClipMap.containsKey(slotName)) {
                host.println(
                  "        WARN: Duplicate device clip name found: \"$slotName\" on track \"${
                    childTrack.name().get()
                  }\". Overwriting."
                )
              }
              host.println(
                "        Mapping device clip: \"$slotName\" to track \"${
                  childTrack.name().get()
                }\" (Track Obj: Exists)"
              )
              deviceClipMap[slotName] = DeviceSlotInfo(slot, childTrack)
            }
          }
        } else if (isGroup && trackName == firePatternGroupName) {
          patternGroupFound = true
          val parentTrackIndex = trackIndex
          host.println("  -> Found PATTERNS group (Index $parentTrackIndex). Processing stored children.")
          val parentStateMap = fireSlotsState.getOrPut(parentTrackIndex) { mutableMapOf() }
          processGroupChildren(
            firePatternGroupName,
            parentTrackIndex,
            childTracks[parentTrackIndex],
            childSlots[parentTrackIndex]
          ) { _, childIndex, slot, slotIndex ->
            val slotName = slot.name().get()
            host.println("      Slot $slotIndex: Name=\"$slotName\" (Exists: ${slot.exists().get()})")
            val patternTrackStateMap = parentStateMap.getOrPut(childIndex) { mutableMapOf() }
            val patternSlotState = patternTrackStateMap.getOrPut(slotIndex) { FireSlotState() }
            if (patternSlotState.name != slotName) {
              host.println("        Updating stored name for [$parentTrackIndex, $childIndex, $slotIndex] from \"${patternSlotState.name}\" to \"$slotName\"")
              patternSlotState.name = slotName
            } else {
              host.println("        Stored name for [$parentTrackIndex, $childIndex, $slotIndex] (\"${patternSlotState.name}\") matches current name (\"$slotName\"). No update needed.")
            }
            if (patternSlotState.name.isNotEmpty() && patternSlotState.isPlaying == true && deviceClipMap.containsKey(
                patternSlotState.name
              )
            ) {
              host.println("        Remap Check: Pattern slot [$parentTrackIndex, $childIndex, $slotIndex] (\"${patternSlotState.name}\") is playing and device mapped. Triggering launch.")
              patternSlotState.deviceSlot = findAndLaunchDeviceClip(patternSlotState.name)
            }
          }
        }
      }
    } catch (e: Exception) {
      host.println("!!! Error during manual clip remapping: ${e.message}")
      e.printStackTrace()
    }
    if (!deviceGroupFound) {
      host.println("WARN: '$devicesGroupName' group track not found during remap.")
    }
    if (!patternGroupFound) {
      host.println("WARN: '$firePatternGroupName' group track not found during remap.")
    }
    host.println("--- Manual Clip Remapping Finished. Device clips mapped: ${deviceClipMap.size} ---")
    host.println("--- Mapped Device Clips ---")
    deviceClipMap.forEach { (key: String, value: DeviceSlotInfo) ->
      try {
        host.println("   - \"$key\" on track \"${value.track.name().get()}\"")
      } catch (e: Exception) {
        host.println("   - \"$key\" (Error getting track name: ${e.message})")
      }
    }
    host.println("--- Pattern Slot Names After Remap ---")
    logNestedMapSummary(host, fireSlotsState, "Fire Slots State Structure")
  }

  private fun setupSettings() {
    val preferences = host.preferences
    val tracksSetting = preferences.getNumberSetting(
      "Tracks",
      "Number of Tracks per Group",
      1.0,
      200.0,
      1.0,
      "",
      25.0
    )
    val rootGroupsSetting = preferences.getNumberSetting(
      "Tracks",
      "Number of root Groups",
      1.0,
      200.0,
      1.0,
      "",
      25.0
    )
    val slotsSetting = preferences.getNumberSetting(
      "Slots",
      "Number of Slots per Track",
      1.0,
      500.0,
      1.0,
      "",
      25.0
    )

    fun addObserverForSetting(name: String, writeValue: (newValue: Int) -> Unit, setting: SettableRangedValue) {
      setting.addRawValueObserver { value: Double ->
        writeValue(value.toInt())
        host.println(
          "Number of $name changed to ${value.toInt()}. " +
                  "Please restart the extension for changes to take effect."
        )
      }
      writeValue(setting.raw.toInt())
    }
    addObserverForSetting("tracks", { numTracks = it }, tracksSetting)
    addObserverForSetting("slots", { numSlots = it }, slotsSetting)
    addObserverForSetting("root groups", { numRootTracks = it }, rootGroupsSetting)

    val stopKeywordSetting = preferences.getStringSetting(
      "Pattern",
      "Stop Keyword",
      1024,
      "[stop]"
    )
    stopKeywordSetting.addValueObserver { value ->
      stopKeyword = value
      host.println("Stop keyword changed to \"$value\"")
    }
    // Initialize stopKeyword with the preference value
    stopKeyword = stopKeywordSetting.get()

    host.println("Tracks: $numTracks, Slots: $numSlots, Root groups: $numRootTracks, Stop keyword: \"$stopKeyword\"")

    setupKeepDevicesPlayingSetting()
  }

  private fun setupKeepDevicesPlayingSetting() {
    val documentState = host.documentState
    val setting = documentState.getEnumBasedBooleanSetting(
      "Keep Devices Playing on Pattern Stop",
      "Mapping",
      false
    )
    this.keepDevicesPlayingSetting = setting
    fun booleanToString(value: Boolean): String = if (value) "enabled" else "disabled"

    setting.addValueObserver { value ->
      host.showPopupNotification("Keep Devices Playing: ${booleanToString(value)}") // Show the actual enum value
      host.println("Setting 'Keep Devices Playing on Pattern Stop' changed to: $value")
    }
    host.println("Initial 'Keep Devices Playing on Pattern Stop' state: ${booleanToString(setting.get())}")
  }

  override fun init() {
    host.println("=== Pattern Tracker Extension v$versionFromProperties Starting ===")

    try {
      // --- Setup Transport Observer ---
      val transport = host.createTransport()
      transport.isPlaying().markInterested()
      transport.isPlaying().addValueObserver { playing ->
        host.println("Transport isPlaying changed: $playing")
        isTransportPlaying = playing
        handleTransportStateChange(playing)
      }
      isTransportPlaying = transport.isPlaying().get() // Initial state
      host.println("Initial Transport State: isPlaying=$isTransportPlaying")

      setupSettings()

      // --- Setup Action Button (Existing Remap Clips) ---
      host.println("Setting up remap action button...")
      val documentState = host.documentState
      val remapAction = documentState.getSignalSetting(
        "Mapping",
        "Remap Clips",
        "Scan tracks and map clips by name"
      )
      remapAction.addSignalObserver(::remapClips)
      host.println("Remap action button setup complete.")

      host.println("Clearing existing references...")

      topLevelTracks.clear()
      childTracks.clear()
      childSlots.clear()

      host.println("Getting project and root track group...")

      val project = host.project
      val rootTrackGroup = project.rootTrackGroup

      host.println("Creating main track bank with size: $numRootTracks")

      val mainTrackBank = rootTrackGroup.createTrackBank(numRootTracks, 0, 0, false)

      host.println("Setting main track bank scroll position...")

      mainTrackBank.scrollPosition().set(0)

      host.println("Starting to process tracks...")

      for (mainTrackIndex in 0 until mainTrackBank.sizeOfBank) {
        val mainTrack = mainTrackBank.getItemAt(mainTrackIndex)

        host.println("root track ${mainTrack.name()} found.")

        topLevelTracks[mainTrackIndex] = mainTrack
        childTracks.getOrPut(mainTrackIndex) { mutableMapOf() }
        childSlots.getOrPut(mainTrackIndex) { mutableMapOf() }

        val currentChildTrackMap = childTracks[mainTrackIndex]
          ?: throw IllegalStateException("$mainTrackIndex not found in map")
        val currentChildSlotMap = childSlots[mainTrackIndex]
          ?: throw IllegalStateException("$mainTrackIndex not found in map")

        mainTrack.exists().markInterested()
        mainTrack.name().markInterested()
        mainTrack.isGroup().markInterested()

        try {
          val childBank = mainTrack.createTrackBank(numTracks, 0, numSlots, true)
          childBank.scrollPosition().set(0)
          childBank.setShouldShowClipLauncherFeedback(true)

          for (childIndex in 0 until childBank.sizeOfBank) {
            val childTrack = childBank.getItemAt(childIndex)
            currentChildTrackMap[childIndex] = childTrack
            currentChildSlotMap.getOrPut(childIndex) { mutableMapOf() }

            val currentSlotMap = currentChildSlotMap[childIndex]!!

            childTrack.exists().markInterested()
            childTrack.name().markInterested()
            childTrack.isGroup().markInterested()

            val slotBank = childTrack.clipLauncherSlotBank()
            slotBank.scrollPosition().set(0)

            for (slotIndex in 0 until slotBank.sizeOfBank) {
              val slot = slotBank.getItemAt(slotIndex)
              currentSlotMap[slotIndex] = slot

              with(slot) {
                exists().markInterested()
                name().markInterested()
                hasContent().markInterested()
                isPlaying().markInterested()
                isPlaybackQueued().markInterested()
                // --- Add slot observers ---
                name().addValueObserver { name ->
                  if (!mainTrack.exists().get() || !childTrack.exists().get()) return@addValueObserver
                  if (mainTrack.name().get() == devicesGroupName && mainTrack.isGroup().get() && !childTrack.isGroup()
                      .get()
                  ) {
                    setupDeviceSlotMappingLogic(name, hasContent().get(), slot, childTrack, slotIndex)
                  }
                }
                hasContent().addValueObserver { hasContent ->
                  if (!mainTrack.exists().get() || !childTrack.exists().get()) return@addValueObserver
                  if (mainTrack.name().get() == devicesGroupName && mainTrack.isGroup().get() && !childTrack.isGroup()
                      .get()
                  ) {
                    setupDeviceSlotMappingLogic(name().get(), hasContent, slot, childTrack, slotIndex)
                  }
                }
                // Fire Pattern Slot Triggering Logic Observer Setup
                val fireParentTrackIndex = mainTrackIndex
                val fireChildTrackIndex = childIndex
                val setupPatternObserverState = { ->
                  fireSlotsState
                    .getOrPut(fireParentTrackIndex) { mutableMapOf() }
                    .getOrPut(fireChildTrackIndex) { mutableMapOf() }
                    .getOrPut(slotIndex) { FireSlotState() }
                }
                name().addValueObserver { name ->
                  if (!mainTrack.exists().get() || !childTrack.exists().get()) return@addValueObserver
                  if (mainTrack.name().get() == firePatternGroupName && mainTrack.isGroup()
                      .get() && !childTrack.isGroup()
                      .get()
                  ) {
                    val patternSlotState = setupPatternObserverState()
                    setupFireSlotNameLogic(patternSlotState, name, fireParentTrackIndex, fireChildTrackIndex, slotIndex)
                  }
                }
                isPlaying().addValueObserver { isPlaying ->
                  if (!mainTrack.exists().get() || !childTrack.exists().get()) return@addValueObserver
                  if (mainTrack.name().get() == firePatternGroupName && mainTrack.isGroup()
                      .get() && !childTrack.isGroup()
                      .get()
                  ) {
                    val patternSlotState = setupPatternObserverState()
                    setupFireSlotPlayingLogic(
                      patternSlotState,
                      isPlaying,
                      fireParentTrackIndex,
                      fireChildTrackIndex,
                      slotIndex
                    )
                  }
                }
              }
            }
          }
        } catch (e: Exception) {
          host.println("Error during init observer/reference setup for top-level track $mainTrackIndex: ${e.message}")
          e.printStackTrace()
        }
      }

      logStoredReferences()
      host.println("Initialization complete. References stored. Observers attached. Press 'Remap Clips' button to perform initial mapping.")
    } catch (e: Exception) {
      host.println("Error during initialization: ${e.message}")
      e.printStackTrace()
    }
  }

  private fun logStoredReferences() {
    host.println("--- Stored References After Init ---")
    host.println("Top Level Tracks (${topLevelTracks.size}):")
    topLevelTracks.forEach { (idx: Int, track: Track) ->
      try {
        host.println(
          "  [$idx]: Name=\"${track.name().get()}\", IsGroup=${
            track.isGroup().get()
          }, Exists=${track.exists().get()}"
        )
      } catch (e: Exception) {
        host.println("  [$idx]: Error getting info: ${e.message}")
      }
    }
    host.println("Child Tracks (${childTracks.size} groups):")
    childTracks.forEach { (parentIdx: Int, childMap: MutableMap<Int, Track>) ->
      host.println("  Group $parentIdx (${childMap.size} children):")
      childMap.forEach { (childIdx: Int, childTrack: Track) ->
        try {
          host.println(
            "    [$parentIdx, $childIdx]: Name=\"${childTrack.name().get()}\", IsGroup=${
              childTrack.isGroup().get()
            }, Exists=${childTrack.exists().get()}"
          )
        } catch (e: Exception) {
          host.println("    [$parentIdx, $childIdx]: Error getting info: ${e.message}")
        }
      }
    }
    logNestedMapSummary(host, childSlots, "  Child Slots Structure")
    host.println("-----------------------------------")
  }

  // --- Refactored Setup Logic ---
  private fun setupDeviceSlotMappingLogic(
    currentSlotName: String,
    currentHasContent: Boolean,
    slot: ClipLauncherSlot,
    track: Track, // The actual device track
    slotIndex: Int
  ) {
    try {
      host.println("   -> DeviceMappingLogic called for slot $slotIndex: name=\"$currentSlotName\", hasContent=$currentHasContent")
      val currentMappingEntry = deviceClipMap.entries.find { it.value.slot == slot }

      if (currentMappingEntry != null && (currentMappingEntry.key != currentSlotName || !currentHasContent || currentSlotName.isEmpty())) {
        host.println("    -> Unmapping device clip: \"${currentMappingEntry.key}\"")
        deviceClipMap.remove(currentMappingEntry.key)
      }

      if (currentHasContent && currentSlotName.isNotEmpty()) {
        val existingMappingForName = deviceClipMap[currentSlotName]
        if (existingMappingForName == null || existingMappingForName.slot != slot) {
          if (existingMappingForName != null) {
            host.println("    -> Name conflict: Unmapping old clip for \"$currentSlotName\"")
            deviceClipMap.remove(currentSlotName)
          }
          host.println("    -> Mapping device clip: \"$currentSlotName\"")
          deviceClipMap[currentSlotName] = DeviceSlotInfo(slot, track)
        }
      }
    } catch (e: Exception) {
      host.println("Error in setupDeviceSlotMappingLogic for slot $slotIndex: ${e.message}")
    }
  }

  // Helper to handle stop command logic
  private fun handleStopCommand(slotState: FireSlotState, name: String): Boolean {
    if (!name.startsWith(stopKeyword)) {
      return false // Not a stop command
    }

    val trackName = name.substring(stopKeyword.length).trim()
    if (trackName.isNotEmpty()) {
      host.println("   -> Stop command detected for track \"$trackName\".")
      val deviceInfo = deviceClipMap.values.find { info -> info.track.name().get() == trackName }
      if (deviceInfo != null) {
        host.println("   -> Found device track. Stopping playback.")
        deviceInfo.track.stop()
      } else {
        host.println("   -> Could not find device track named \"$trackName\".")
      }
    } else {
      host.println("   -> Invalid stop command format. Expected \"$stopKeyword<TrackName>\".")
    }
    slotState.deviceSlot = null // Clear associated device slot regardless of success
    return true // Stop command was handled (or attempted)
  }

  // Helper to handle launch command logic
  private fun handleLaunchCommand(slotState: FireSlotState, name: String) {
    if (!isTransportPlaying) {
      host.println("   -> Transport not playing. Launch postponed for device clip \"$name\".")
      return // Don't launch if transport is stopped
    }
    val isDeviceMapped = deviceClipMap.containsKey(name)
    host.println("   -> Play event check on name change. (Transport Playing: $isTransportPlaying, Name Known: true, Device Mapped: $isDeviceMapped)")
    if (isDeviceMapped) {
      host.println("   -> Preconditions met. Launching device clip \"$name\".")
      slotState.deviceSlot = findAndLaunchDeviceClip(name)
    } else {
      host.println("   -> Launch postponed: Device clip \"$name\" not yet mapped.")
    }
  }

  // --- Transport State Change Handler ---
  private fun handleTransportStateChange(transportIsPlaying: Boolean) {
    host.println("Handling transport state change. Transport isPlaying: $transportIsPlaying")
    fireSlotsState.forEach { (_, parentMap) ->
      parentMap.forEach { (_, childMap) ->
        childMap.forEach { (_, slotState) ->
          if (slotState.isPlaying == true && slotState.name.isNotEmpty() && slotState.name != stopKeyword) {
            if (transportIsPlaying) {
              host.println("   Transport started. Re-triggering device clip for pattern: ${slotState.name}")
              slotState.deviceSlot = findAndLaunchDeviceClip(slotState.name)
            } else {
              host.println("   Transport stopped. Stopping device clip for pattern: ${slotState.name}")
              findAndStopDeviceClip(slotState.name)
              // We keep slotState.deviceSlot as is, because the pattern slot itself is still "playing"
              // and should resume if transport starts again, unless the pattern slot itself stops.
            }
          }
        }
      }
    }
  }

  private fun setupFireSlotNameLogic(
    slotState: FireSlotState,
    name: String,
    parentTrackIndex: Int,
    childTrackIndex: Int,
    slotIndex: Int
  ) {
    try {
      val oldName = slotState.name
      slotState.name = name

      host.println("Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] name changed from \"${oldName.ifEmpty { "<init>" }}\" to \"${name.ifEmpty { "<empty>" }}\"")

      // Only process if the slot is currently playing and has a name
      if (name.isEmpty() || slotState.isPlaying != true) {
        return
      }

      // Try to handle as a stop command first
      if (handleStopCommand(slotState, name)) {
        return // Stop command handled, nothing more to do
      }

      // If not a stop command, handle as a potential launch command
      handleLaunchCommand(slotState, name)

    } catch (e: Exception) {
      host.println("Error in fire slot name logic: ${e.message}")
    }
  }

  private fun setupFireSlotPlayingLogic(
    slotState: FireSlotState,
    isPlaying: Boolean,
    parentTrackIndex: Int,
    childTrackIndex: Int,
    slotIndex: Int
  ) {
    try {
      // --- Initial State Handling ---
      if (slotState.isPlaying == null) {
        slotState.isPlaying = isPlaying
        host.println("Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] initial isPlaying state: $isPlaying")
        if (isPlaying) {
          val isNameKnown = slotState.name.isNotEmpty()
          val isDeviceMapped = isNameKnown && deviceClipMap.containsKey(slotState.name)
          host.println("   -> Initial state is playing. Checking preconditions (Transport Playing: $isTransportPlaying, Name Known: $isNameKnown, Device Mapped: $isDeviceMapped)")
          if (isNameKnown && isDeviceMapped && isTransportPlaying) { // Check transport state
            host.println("   -> Preconditions met for initial state. Launching device clip \"${slotState.name}\".")
            slotState.deviceSlot = findAndLaunchDeviceClip(slotState.name)
          } else {
            host.println("   -> Preconditions not met for initial state launch (Transport Playing: $isTransportPlaying).")
          }
        }
        return
      }

      // --- State Change Handling ---
      if (isPlaying == slotState.isPlaying) return // No change

      slotState.isPlaying = isPlaying
      val currentName = slotState.name.ifEmpty { "<name unknown>" }
      host.println("Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] \"$currentName\" isPlaying changed to: $isPlaying")

      if (isPlaying) {
        // --- Handle Play Event ---
        val isNameKnown = slotState.name.isNotEmpty()
        val isDeviceMapped = isNameKnown && deviceClipMap.containsKey(slotState.name)
        host.println("   -> Play event. Checking preconditions (Transport Playing: $isTransportPlaying, Name Known: $isNameKnown, Device Mapped: $isDeviceMapped)")
        if (isNameKnown && isDeviceMapped && isTransportPlaying) { // Check transport state
          host.println("   -> Preconditions met. Launching device clip \"${slotState.name}\".")
          slotState.deviceSlot = findAndLaunchDeviceClip(slotState.name)
        } else {
          host.println("   -> Launch postponed: Device clip \"${slotState.name}\" not yet mapped or transport stopped.")
        }
      } else {
        // --- Handle Stop Event ---
        host.println("   -> Stop event for pattern \"$currentName\"")

        // If this was a stop command clip finishing, we don't need to do anything further
        if (currentName.startsWith(stopKeyword)) {
          host.println("   -> Stop command clip finished. No further action needed.")
          slotState.deviceSlot = null // Ensure reference is cleared
          return
        }

        // Otherwise, stop the associated device clip IF the setting is OFF
        if (this.keepDevicesPlayingSetting?.get() == false) {
          host.println("   -> 'Keep Devices Playing' is OFF. Stopping device clip for pattern \"${slotState.name}\".")
          findAndStopDeviceClip(slotState.name)
        } else {
          host.println("   -> 'Keep Devices Playing' is ON. Device clip for pattern \"${slotState.name}\" will continue.")
        }
        // We still clear the script's direct reference to the deviceSlot here because this *pattern slot* has stopped.
        // The device clip might continue playing due to the setting, but this specific pattern instance is done triggering it.
        slotState.deviceSlot = null

        // Note: We removed the complex logic trying to find the track via name again,
        // as findAndStopDeviceClip should handle the case where the name->device mapping exists.
        // If slotState.deviceSlot was already null, findAndStopDeviceClip uses the name mapping.
      }
    } catch (e: Exception) {
      host.println("Error in fire slot isPlaying logic: ${e.message}")
    }
  }

  override fun exit() {
      host.println("=== Pattern Tracker Extension v$versionFromProperties Exited ===")
  }

  override fun flush() {
    // Currently unused
  }
} 