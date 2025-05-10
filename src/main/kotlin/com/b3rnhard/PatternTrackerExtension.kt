package com.b3rnhard

import com.bitwig.extension.controller.ControllerExtension
import com.bitwig.extension.controller.api.*
import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.DEVICES_GROUP_NAME
import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.FIRE_PATTERN_GROUP_NAME
import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.MAX_SLOTS
import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.MAX_TRACKS
import com.b3rnhard.PatternTrackerExtensionDefinition.Companion.SCRIPT_VERSION

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


class PatternTrackerExtension(definition: PatternTrackerExtensionDefinition, host: ControllerHost)
    : ControllerExtension(definition, host) {

    // --- State ---
    private val deviceClipMap: MutableMap<String, DeviceSlotInfo> = mutableMapOf()
    private val fireSlotsState: TrackSlotItemMap<FireSlotState> = mutableMapOf() // Corrected Type

    // Storing references obtained during init
    private val topLevelTracks: MutableMap<Int, Track> = mutableMapOf()
    private val childTracks: TrackSlotMap<Track> = mutableMapOf()
    // Corrected Type: Needs to store ClipLauncherSlot, not FireSlotState
    private val childSlots: TrackSlotItemMap<ClipLauncherSlot> = mutableMapOf()


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
                host.println("Launching device clip \"$fireClipName\" on track \"${deviceInfo.track.name().get()}\" with no quantization.")
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

    private fun findAndStopDeviceClip(deviceSlot: ClipLauncherSlot, fireClipName: String) {
        val deviceInfo = deviceClipMap[fireClipName]
        try {
            if (deviceInfo?.track?.exists()?.get() == true) {
                host.println("Stopping playback on device track \"${deviceInfo.track.name().get()}\" for clip \"$fireClipName\"")
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

    // --- Remapping Function ---
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

            topLevelTracks.forEach { (trackIndex: Int, track: Track) ->
                if (!track.exists().get()) {
                    host.println("Remap: Skipping non-existent top-level track index $trackIndex")
                    return@forEach // continue
                }

                val trackName = track.name().get()
                val isGroup = track.isGroup().get()
                host.println("Remap: Processing stored top-level track $trackIndex: Name=\"$trackName\", IsGroup=$isGroup")

                // --- DEVICES Group Logic ---
                if (isGroup && trackName == DEVICES_GROUP_NAME) {
                    deviceGroupFound = true
                    host.println("  -> Found DEVICES group (Index $trackIndex). Processing stored children.")
                    val childrenOfGroup = childTracks[trackIndex]
                    val slotsOfGroup = childSlots[trackIndex]

                    if (childrenOfGroup == null || slotsOfGroup == null) {
                        host.println("    WARN: No stored child tracks or slots found for Devices group index $trackIndex")
                        return@forEach // continue
                    }

                    childrenOfGroup.forEach { (childIndex: Int, childTrack: Track) ->
                        if (!childTrack.exists().get() || childTrack.isGroup().get()) {
                            host.println("    Skipping non-existent or nested group child track index $childIndex")
                            return@forEach // continue inner loop
                        }
                        val childTrackName = childTrack.name().get()
                        host.println("    Devices Child Track $childIndex: Name=\"$childTrackName\" (Exists: ${childTrack.exists().get()})")
                        val slotsOfChild = slotsOfGroup[childIndex]

                        if (slotsOfChild == null) {
                            host.println("      WARN: No stored slots found for Devices child track index $childIndex")
                            return@forEach // continue inner loop
                        }

                        slotsOfChild.forEach { (slotIndex: Int, slot: ClipLauncherSlot) ->
                            if (!slot.exists().get()) {
                                host.println("        Skipping non-existent slot index $slotIndex")
                                return@forEach // continue innermost loop
                            }
                            val slotName = slot.name().get()
                            val hasContent = slot.hasContent().get()
                            host.println("      Slot $slotIndex: Name=\"$slotName\", HasContent=$hasContent (Exists: ${slot.exists().get()})")

                            if (hasContent && slotName.isNotEmpty()) {
                                if (deviceClipMap.containsKey(slotName)) {
                                    host.println("        WARN: Duplicate device clip name found: \"$slotName\" on track \"$childTrackName\". Overwriting.")
                                }
                                host.println("        Mapping device clip: \"$slotName\" to track \"$childTrackName\" (Track Obj: Exists)")
                                deviceClipMap[slotName] = DeviceSlotInfo(slot, childTrack)
                            }
                        }
                    }
                // --- PATTERNS Group Logic ---
                } else if (isGroup && trackName == FIRE_PATTERN_GROUP_NAME) {
                    patternGroupFound = true
                    val parentTrackIndex = trackIndex
                    host.println("  -> Found PATTERNS group (Index $parentTrackIndex). Processing stored children.")
                    val childrenOfGroup = childTracks[parentTrackIndex]
                    val slotsOfGroup = childSlots[parentTrackIndex]

                    if (childrenOfGroup == null || slotsOfGroup == null) {
                        host.println("    WARN: No stored child tracks or slots found for Patterns group index $parentTrackIndex")
                        return@forEach // continue
                    }

                    val parentStateMap = fireSlotsState.getOrPut(parentTrackIndex) { mutableMapOf() }

                    childrenOfGroup.forEach { (childIndex: Int, childTrack: Track) ->
                        if (!childTrack.exists().get() || childTrack.isGroup().get()) {
                            host.println("    Skipping non-existent or nested group child track index $childIndex")
                            return@forEach // continue inner loop
                        }
                        val childTrackName = childTrack.name().get()
                        host.println("    Patterns Child Track $childIndex: Name=\"$childTrackName\" (Exists: ${childTrack.exists().get()})")
                        val slotsOfChild = slotsOfGroup[childIndex]

                        if (slotsOfChild == null) {
                            host.println("      WARN: No stored slots found for Patterns child track index $childIndex")
                            return@forEach // continue inner loop
                        }

                        val patternTrackStateMap = parentStateMap.getOrPut(childIndex) { mutableMapOf() }

                        slotsOfChild.forEach { (slotIndex: Int, slot: ClipLauncherSlot) ->
                            if (!slot.exists().get()) {
                                host.println("        Skipping non-existent slot index $slotIndex")
                                return@forEach // continue innermost loop
                            }
                            val slotName = slot.name().get()
                            host.println("      Slot $slotIndex: Name=\"$slotName\" (Exists: ${slot.exists().get()})")

                            val patternSlotState = patternTrackStateMap.getOrPut(slotIndex) { FireSlotState() }

                            if (patternSlotState.name != slotName) {
                                host.println("        Updating stored name for [$parentTrackIndex, $childIndex, $slotIndex] from \"${patternSlotState.name}\" to \"$slotName\"")
                                patternSlotState.name = slotName
                            } else {
                                host.println("        Stored name for [$parentTrackIndex, $childIndex, $slotIndex] (\"${patternSlotState.name}\") matches current name (\"$slotName\"). No update needed.")
                            }

                            if (patternSlotState.name.isNotEmpty() && patternSlotState.isPlaying == true && deviceClipMap.containsKey(patternSlotState.name)) {
                                host.println("        Remap Check: Pattern slot [$parentTrackIndex, $childIndex, $slotIndex] (\"${patternSlotState.name}\") is playing and device mapped. Triggering launch.")
                                patternSlotState.deviceSlot = findAndLaunchDeviceClip(patternSlotState.name)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            host.println("!!! Error during manual clip remapping: ${e.message}")
            e.printStackTrace()
        }

        if (!deviceGroupFound) {
            host.println("WARN: '$DEVICES_GROUP_NAME' group track not found during remap.")
        }
        if (!patternGroupFound) {
            host.println("WARN: '$FIRE_PATTERN_GROUP_NAME' group track not found during remap.")
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
        fireSlotsState.forEach { (parentIdx: Int, parentMap: TrackSlotMap<FireSlotState>) ->
            parentMap.forEach { (childIdx: Int, childMap: MutableMap<Int, FireSlotState>) ->
                childMap.forEach { (slotIdx: Int, state: FireSlotState) ->
                    host.println("   - Slot [$parentIdx, $childIdx, $slotIdx]: Name=\"${state.name}\" (IsPlaying: ${state.isPlaying})")
                }
            }
        }
        host.println("---------------------------")
    }

    override fun init() {
        host.println("=== Pattern Tracker Extension Starting ===")
        host.println("Version: $SCRIPT_VERSION")
        host.println("Initializing with host: ${host.javaClass.name}")

        try {
            // --- Setup Action Button ---
            host.println("Setting up remap action button...")
            val documentState = host.getDocumentState()
            val remapAction = documentState.getSignalSetting(
                "Mapping",
                "Remap Clips",
                "Scan tracks and map clips by name"
            )
            remapAction.addSignalObserver(::remapClips)
            host.println("Remap action button setup complete")

            host.println("Clearing existing references...")
            topLevelTracks.clear()
            childTracks.clear()
            childSlots.clear()

            host.println("Getting project and root track group...")
            val project = host.getProject()
            val rootTrackGroup = project.getRootTrackGroup()
            host.println("Creating main track bank with size: $MAX_TRACKS")
            val mainTrackBank = rootTrackGroup.createTrackBank(MAX_TRACKS, 0, 0, false)

            host.println("Setting main track bank scroll position...")
            mainTrackBank.scrollPosition().set(0)

            host.println("Starting to process tracks...")

            for (i in 0 until mainTrackBank.getSizeOfBank()) {
                val track = mainTrackBank.getItemAt(i)
                val trackIndex = i

                topLevelTracks[trackIndex] = track
                childTracks.getOrPut(trackIndex) { mutableMapOf() }
                childSlots.getOrPut(trackIndex) { mutableMapOf() }
                val currentChildTrackMap = childTracks[trackIndex]!!
                val currentChildSlotMap = childSlots[trackIndex]!!

                track.exists().markInterested()
                track.name().markInterested()
                track.isGroup().markInterested()

                try {
                    val childBank = track.createTrackBank(MAX_TRACKS, 0, MAX_SLOTS, false)
                    childBank.scrollPosition().set(0)

                    for (j in 0 until childBank.getSizeOfBank()) {
                        val childTrack = childBank.getItemAt(j)
                        val childIndex = j

                        currentChildTrackMap[childIndex] = childTrack
                        currentChildSlotMap.getOrPut(childIndex) { mutableMapOf() }
                        val currentSlotMap = currentChildSlotMap[childIndex]!!

                        childTrack.exists().markInterested()
                        childTrack.name().markInterested()
                        childTrack.isGroup().markInterested()

                        val slotBank = childTrack.clipLauncherSlotBank()
                        slotBank.scrollPosition().set(0)

                        for (k in 0 until slotBank.getSizeOfBank()) {
                            val slot = slotBank.getItemAt(k)
                            val slotIndex = k

                            currentSlotMap[slotIndex] = slot

                            slot.exists().markInterested()
                            slot.name().markInterested()
                            slot.hasContent().markInterested()
                            slot.isPlaying().markInterested()
                            slot.isPlaybackQueued().markInterested()

                            // --- Add slot observers ---
                            slot.name().addValueObserver { name ->
                                 if (!track.exists().get() || !childTrack.exists().get()) return@addValueObserver
                                 if (track.name().get() == DEVICES_GROUP_NAME && track.isGroup().get() && !childTrack.isGroup().get()) {
                                    setupDeviceSlotMappingLogic(name, slot.hasContent().get(), slot, childTrack, slotIndex)
                                 }
                            }
                            slot.hasContent().addValueObserver { hasContent ->
                                 if (!track.exists().get() || !childTrack.exists().get()) return@addValueObserver
                                 if (track.name().get() == DEVICES_GROUP_NAME && track.isGroup().get() && !childTrack.isGroup().get()) {
                                    setupDeviceSlotMappingLogic(slot.name().get(), hasContent, slot, childTrack, slotIndex)
                                 }
                            }

                            // Fire Pattern Slot Triggering Logic Observer Setup
                            val fireParentTrackIndex = trackIndex
                            val fireChildTrackIndex = childIndex

                            val setupPatternObserverState = { ->
                                fireSlotsState
                                    .getOrPut(fireParentTrackIndex) { mutableMapOf() }
                                    .getOrPut(fireChildTrackIndex) { mutableMapOf() }
                                    .getOrPut(slotIndex) { FireSlotState() }
                            }

                            slot.name().addValueObserver { name ->
                                if (!track.exists().get() || !childTrack.exists().get()) return@addValueObserver
                                if (track.name().get() == FIRE_PATTERN_GROUP_NAME && track.isGroup().get() && !childTrack.isGroup().get()) {
                                    val patternSlotState = setupPatternObserverState()
                                    setupFireSlotNameLogic(patternSlotState, name, fireParentTrackIndex, fireChildTrackIndex, slotIndex)
                                }
                            }

                            slot.isPlaying().addValueObserver { isPlaying ->
                               if (!track.exists().get() || !childTrack.exists().get()) return@addValueObserver
                               if (track.name().get() == FIRE_PATTERN_GROUP_NAME && track.isGroup().get() && !childTrack.isGroup().get()) {
                                    val patternSlotState = setupPatternObserverState()
                                    setupFireSlotPlayingLogic(patternSlotState, isPlaying, fireParentTrackIndex, fireChildTrackIndex, slotIndex)
                                }
                            }
                        } // End slot loop (k)
                    } // End child track loop (j)
                } catch (e: Exception) {
                    host.println("Error during init observer/reference setup for top-level track $trackIndex: ${e.message}")
                    e.printStackTrace()
                }
            } // End top-level track loop (i)

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
                host.println("  [$idx]: Name=\"${track.name().get()}\", IsGroup=${track.isGroup().get()}, Exists=${track.exists().get()}")
            } catch (e: Exception) {
                host.println("  [$idx]: Error getting info: ${e.message}")
            }
        }
        host.println("Child Tracks (${childTracks.size} groups):")
        childTracks.forEach { (parentIdx: Int, childMap: MutableMap<Int, Track>) ->
            host.println("  Group $parentIdx (${childMap.size} children):")
            childMap.forEach { (childIdx: Int, childTrack: Track) ->
                try {
                    host.println("    [$parentIdx, $childIdx]: Name=\"${childTrack.name().get()}\", IsGroup=${childTrack.isGroup().get()}, Exists=${childTrack.exists().get()}")
                } catch (e: Exception) {
                    host.println("    [$parentIdx, $childIdx]: Error getting info: ${e.message}")
                }
            }
        }
        host.println("Child Slots (${childSlots.size} groups):")
        childSlots.forEach { (parentIdx: Int, groupSlotMap: MutableMap<Int, MutableMap<Int, ClipLauncherSlot>>) ->
            host.println("  Group $parentIdx (${groupSlotMap.size} tracks with slots):")
            groupSlotMap.forEach { (childIdx: Int, slotMap: MutableMap<Int, ClipLauncherSlot>) ->
                host.println("    Track $childIdx (${slotMap.size} slots):")
                slotMap.forEach { (slotIdx: Int, slot: ClipLauncherSlot) ->
                    try {
                        host.println("      [$parentIdx, $childIdx, $slotIdx]: Name=\"${slot.name().get()}\", HasContent=${slot.hasContent().get()}, Exists=${slot.exists().get()}")
                    } catch (e: Exception) {
                        host.println("      [$parentIdx, $childIdx, $slotIdx]: Error getting info: ${e.message}")
                    }
                }
            }
        }
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

            if (name.isNotEmpty() && slotState.isPlaying == true) {
                val isDeviceMapped = deviceClipMap.containsKey(name)
                host.println("   -> Play event check on name change. (Name Known: true, Device Mapped: $isDeviceMapped)")
                if (isDeviceMapped) {
                    host.println("   -> Preconditions met. Launching device clip \"$name\".")
                    slotState.deviceSlot = findAndLaunchDeviceClip(name)
                } else {
                    host.println("   -> Launch postponed: Device clip \"$name\" not yet mapped.")
                }
            }
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
            if (slotState.isPlaying == null) {
                slotState.isPlaying = isPlaying
                host.println("Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] initial isPlaying state: $isPlaying")
                if (isPlaying) {
                    val isNameKnown = slotState.name.isNotEmpty()
                    val isDeviceMapped = isNameKnown && deviceClipMap.containsKey(slotState.name)
                    host.println("   -> Initial state is playing. Checking preconditions (Name Known: $isNameKnown, Device Mapped: $isDeviceMapped)")
                    if (isNameKnown && isDeviceMapped) {
                        host.println("   -> Preconditions met for initial state. Launching device clip \"${slotState.name}\".")
                        slotState.deviceSlot = findAndLaunchDeviceClip(slotState.name)
                    } else {
                        host.println("   -> Preconditions not met for initial state launch.")
                    }
                }
                return
            }

            if (isPlaying == slotState.isPlaying) return

            slotState.isPlaying = isPlaying

            val currentName = slotState.name.ifEmpty { "<name unknown>" }
            host.println("Fire slot [$parentTrackIndex, $childTrackIndex, $slotIndex] \"$currentName\" isPlaying changed to: $isPlaying")

            if (isPlaying) {
                 val isNameKnown = slotState.name.isNotEmpty()
                 val isDeviceMapped = isNameKnown && deviceClipMap.containsKey(slotState.name)
                 host.println("   -> Play event. Checking preconditions (Name Known: $isNameKnown, Device Mapped: $isDeviceMapped)")
                 if (isNameKnown && isDeviceMapped) {
                     host.println("   -> Preconditions met. Launching device clip \"${slotState.name}\".")
                     slotState.deviceSlot = findAndLaunchDeviceClip(slotState.name)
                 } else {
                     host.println("   -> Launch postponed: Device clip \"${slotState.name}\" not yet mapped.")
                 }
            } else {
                host.println("   -> Stop event for \"$currentName\"")
                val activeDeviceSlot = slotState.deviceSlot
                if (activeDeviceSlot != null) {
                    host.println("   -> Associated deviceSlot found. Stopping device clip.")
                    findAndStopDeviceClip(activeDeviceSlot, slotState.name)
                    slotState.deviceSlot = null
                } else {
                    host.println("   -> No active deviceSlot recorded. Attempting track stop based on current name mapping.")
                    if (slotState.name.isNotEmpty()) {
                        val deviceInfo = deviceClipMap[slotState.name]
                        if (deviceInfo?.track?.exists()?.get() == true) {
                            host.println("   -> Stopping track \"${deviceInfo.track.name().get()}\" based on current mapping for \"${slotState.name}\".")
                            deviceInfo.track.stop()
                        } else {
                            host.println("   -> Could not find track to stop based on name \"${slotState.name}\".")
                        }
                    } else {
                        host.println("   -> Cannot stop track as pattern clip name is unknown.")
                    }
                }
            }
        } catch (e: Exception) {
            host.println("Error in fire slot isPlaying logic: ${e.message}")
        }
    }

    override fun exit() {
        host.println("Tracker Pattern Trigger Exiting... (v$SCRIPT_VERSION)")
    }

    override fun flush() {
        // Currently unused
    }
} 