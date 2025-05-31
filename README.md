# Bitwig Studio Extensions

This project contains three Bitwig Studio controller extensions that enhance your music production workflow.

## Extensions Overview

### 1. Pattern Tracker

A sophisticated pattern-based music creation system that automates device triggering based on pattern names. Enables seamless integration between pattern clips and device/instrument clips for complex musical arrangements.

**Core Concept**: Empty trigger clips in a "Patterns" group automatically launch corresponding content clips in a "Devices" group when their names match, creating synchronized pattern-based workflows.

#### Key Features

- **Name-Based Triggering**: Pattern clips (usually empty) trigger device clips with matching names
- **Transport-Aware Operations**: Device clips only trigger when transport is playing; stop when transport stops
- **Trigger-Based Workflow**: Use empty pattern clips as triggers for complex musical arrangements
- **Content Separation**: Keep triggers separate from actual musical content for better organization
- **Stop Commands**: Use `[stop] TrackName` to stop specific device tracks
- **Configurable Persistence**: Choose whether device clips continue playing after pattern clips end
- **Live Performance Ready**: Real-time pattern switching with immediate device response
- **Flexible Track Organization**: Supports multiple pattern tracks and device tracks simultaneously
- **Session Integration**: Works seamlessly with Bitwig's session view and clip launcher

#### Advanced Features

- **Remap Function**: Manual remapping of clips by name with one-click button
- **Transport Synchronization**: Patterns resume device playback when transport starts again
- **Multiple Pattern Types**: Support for both trigger patterns and stop commands
- **Dynamic Mapping**: Real-time updates when clip names change
- **Error Handling**: Graceful handling of missing clips or invalid mappings
- **Configurable Stop Behavior**: Keep devices playing or stop them when patterns end

### 2. Step Recording Extension

Converts keyboard input into step-sequenced notes in Bitwig Studio clips:

- **Note Input**: Play notes on your MIDI keyboard to add them to the currently selected clip
- **Cursor Navigation**: Automatically advances cursor position after note entry
- **Chord Detection**: Notes played within 100ms are placed as chords
- **Configurable Note Length**: Set note duration from 1/64 to whole notes
- **MIDI Learn**: Map hardware buttons for cursor forward/backward movement
- **Step Sequencing**: Creates precise step-based note sequences
- **Real-time Recording**: Input notes while maintaining quantized timing
- **Note Replacement**: New notes at the same position replace existing ones

### 3. MIDI Splitter

Enables using one hardware MIDI controller with multiple Bitwig extensions simultaneously:

- **MIDI Routing**: Receives MIDI input from one hardware device and forwards to multiple virtual outputs
- **Multi-Extension Support**: Use your controller with original manufacturer extension plus custom extensions
- **Real-time Processing**: Low-latency MIDI forwarding with no noticeable delay
- **Complete MIDI Support**: Forwards all MIDI message types including Note On/Off, CC, and SysEx
- **Virtual Port Integration**: Works with LoopMIDI, IAC Driver, or snd-virmidi
- **Simultaneous Operation**: Hardware controller functions normally while custom extensions receive input
- **Transparent Operation**: Hardware LEDs, displays, and feedback work through original extension

**Use case**: Use your Push 2, Launchpad, or other controller with both its original manufacturer extension AND custom extensions like the Step Recording Extension at the same time.

## MIDI Splitter Setup Guide

The MIDI Splitter is the key to using multiple extensions with one controller. Here's how to set it up:

### What You Need

- **Windows**: [LoopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html) (free)
- **Mac**: Built-in IAC Driver (Audio MIDI Setup)
- **Linux**: `snd-virmidi` module

### Setup Overview

```
Hardware Controller (e.g., Push 2)
          ↓
    MIDI Splitter Extension
          ↓
    ┌─────────────┬─────────────┐
    ↓             ↓             ↓
Virtual Port A  Virtual Port B  Virtual Port C...
    ↓             ↓             ↓
Original        Step Recording   Other Custom
Extension       Extension         Extensions
```

### Step 1: Install LoopMIDI (Windows)

1. Download and install [LoopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html)
2. Run LoopMIDI to open the control panel

### Step 2: Create Virtual MIDI Ports

In LoopMIDI:

1. **Create Port A**:
   - Port name: `Virtual-A-Original`
   - Click **[+]** to add
2. **Create Port B**:

   - Port name: `Virtual-B-Custom`
   - Click **[+]** to add

3. **Verify**: Both virtual ports should now be listed and active

### Step 3: Configure Extensions in Bitwig

#### Add MIDI Splitter Extension

1. Open **Bitwig Studio → Dashboard → Controllers**
2. Click **[+ Add controller]**
3. Find **Generic → MIDI Splitter**
4. **Input**: Assign to your **real hardware device** (e.g., "Ableton Push 2")
5. **Output 1**: Assign to `Virtual-A-Original`
6. **Output 2**: Assign to `Virtual-B-Custom`
7. Click **Add**

#### Add Original Hardware Extension

1. Click **[+ Add controller]** again
2. Find your hardware's original extension (e.g., **Ableton → Push 2**)
3. **Input**: Assign to `Virtual-A-Original` (NOT your real hardware!)
4. **Output**: Assign to `Virtual-A-Original`
5. Click **Add**

#### Add Step Recording Extension

1. Click **[+ Add controller]** again
2. Find **Generic → Step Recording Extension**
3. **Input**: Assign to `Virtual-B-Custom`
4. Click **Add**

## Pattern Tracker Usage Guide

The Pattern Tracker extension creates an automated pattern-based music creation system using two special track groups.

### Required Track Setup

The extension requires two specific **Group Tracks** in your Bitwig project:

#### 1. "Devices" Group Track

Create a Group Track named exactly **"Devices"** containing:

- **Device/Instrument tracks** with the actual musical content
- **Content clips** with descriptive names (e.g., "Intro", "Verse A", "Chorus", "Bridge")
- **Audio clips**, **note clips**, **instrument clips**, or **device chains** that make up your patterns
- **Unique names required**: No two clips should have identical names to avoid mapping conflicts
- **Nested groups supported**: You can organize tracks into subgroups for better organization

#### 2. "Patterns" Group Track

Create a Group Track named exactly **"Patterns"** containing:

- **Child tracks** with **pattern clips** that reference device clips by name
- **Pattern clips** are usually empty and serve as triggers only - their names must match device clip names exactly
- **Stop command clips** named `[stop] TrackName` (e.g., `[stop] Bass`, `[stop] Drums`)
- **Content not required**: These clips serve as triggers only - their audio content doesn't matter, only their names
- **Nested groups supported**: You can organize trigger tracks into subgroups for better organization

### Basic Usage Workflow

#### 1. Setup Track Structure

```
Project Root
├── Patterns (Group) - TRIGGER CLIPS
│   ├── Main Patterns (Subgroup)
│   │   ├── Track 1
│   │   │   ├── Slot 1: "Intro" (empty clip - trigger only)
│   │   │   ├── Slot 2: "Verse A" (empty clip - trigger only)
│   │   │   └── Slot 3: "Chorus" (empty clip - trigger only)
│   │   └── Track 2
│   │       ├── Slot 1: "Bridge" (empty clip - trigger only)
│   │       └── Slot 2: "Outro" (empty clip - trigger only)
│   └── Control (Subgroup)
│       └── Stop Commands Track
│           ├── Slot 1: "[stop] Bass" (stop command)
│           └── Slot 2: "[stop] Drums" (stop command)
└── Devices (Group) - CONTENT CLIPS
    ├── Rhythm Section (Subgroup)
    │   ├── Bass Track
    │   │   ├── Slot 1: "Intro" (actual bass content)
    │   │   ├── Slot 2: "Verse A" (actual bass content)
    │   │   └── Slot 3: "Chorus" (actual bass content)
    │   └── Drums Track
    │       ├── Slot 1: "Intro" (actual drum content)
    │       ├── Slot 2: "Verse A" (actual drum content)
    │       └── Slot 3: "Chorus" (actual drum content)
    └── Melody Section (Subgroup)
        ├── Lead Track
        │   ├── Slot 1: "Bridge" (actual lead content)
        │   └── Slot 2: "Outro" (actual lead content)
        └── Harmony Track
            ├── Slot 1: "Bridge" (actual harmony content)
            └── Slot 2: "Outro" (actual harmony content)
```

#### 2. Extension Configuration

1. **Add Extension**:

   - **Bitwig Studio → Dashboard → Controllers**
   - **[+ Add controller] → Generic → Tracker Pattern Trigger**
   - **No MIDI ports required**
   - Click **Add**

2. **Configure Settings** (Studio I/O Panel → Pattern Tracker):

   - **Number of Tracks per Group**: Default 5 (adjust based on your setup)
   - **Number of root Groups**: Default 2 (Patterns + Devices groups)
   - **Number of Slots per Track**: Default 5 (adjust based on your clips)
   - **Stop Keyword**: Default `[stop]` (customize if needed)

3. **Initial Mapping**:
   - Press **"Remap Clips"** button in extension settings
   - Extension scans both groups and maps clips by name

### Advanced Usage

#### Stop Commands

Create clips in the "Patterns" group with special stop command names:

- **`[stop] Bass`**: Stops the device track named "Bass"
- **`[stop] Drums`**: Stops the device track named "Drums"
- **`[stop] Lead Synth`**: Stops the device track named "Lead Synth"

**Important**: The stop command must include the exact track name from the "Devices" group.

#### Pattern Variations

Create pattern variations using descriptive names:

- **"Verse A"**, **"Verse B"**: Different verse variations
- **"Chorus Full"**, **"Chorus Light"**: Intensity variations
- **"Bridge 1"**, **"Bridge 2"**: Multiple bridge sections

### Extension Settings

Access all settings through **Studio I/O Panel → Pattern Tracker**:

#### Track Configuration

- **Number of Tracks per Group**: How many child tracks to scan in each group (1-128)
- **Number of root Groups**: How many top-level groups to monitor (1-16)
- **Number of Slots per Track**: How many clip slots to monitor per track (1-128)

#### Pattern Control

- **Stop Keyword**: Customize the keyword for stop commands (default: `[stop]`)
- **Keep Devices Playing on Pattern Stop**:
  - **DISABLED**: Device clips stop when pattern clips end
  - **ENABLED**: Device clips continue playing until manually stopped

#### Mapping Control

- **Remap Clips**: Manual button to rescan and remap all clips by name
- **Real-time Updates**: Automatic remapping when clip names change

### Transport Behavior

The extension is **transport-aware** and behaves differently based on transport state:

#### When Transport is Playing

- Pattern clips trigger corresponding device clips immediately
- Device clips stop when transport stops (unless "Keep Devices Playing" is enabled)
- Pattern switching works in real-time

#### When Transport is Stopped

- Pattern clip launches are tracked but device clips don't trigger
- When transport starts, currently playing patterns automatically trigger their device clips
- Allows pattern setup while transport is stopped

### Troubleshooting

#### Patterns Not Triggering Devices

1. **Check Group Names**: Ensure groups are named exactly "Patterns" and "Devices"
2. **Check Clip Names**: Verify pattern and device clip names match exactly
3. **Press Remap Clips**: Use the manual remap button to refresh mappings
4. **Check Transport**: Ensure transport is playing
5. **Monitor Console**: Check Bitwig console for extension log messages

#### Clips Not Found

1. **Restart Extension**: Disable and re-enable the extension
2. **Check Track Structure**: Verify groups contain non-group child tracks
3. **Increase Track Limits**: Adjust "Number of Tracks per Group" setting
4. **Check Slot Limits**: Adjust "Number of Slots per Track" setting

#### Performance Issues

1. **Reduce Scan Range**: Lower track and slot count settings
2. **Simplify Setup**: Use fewer pattern and device tracks
3. **Check Transport Sync**: Ensure consistent transport state

### Tips and Best Practices

#### Organization

- **Use clear naming**: "Intro", "Verse", "Chorus" are better than "Pattern1", "Pattern2"
- **Keep triggers simple**: Pattern clips can be empty - only their names matter
- **Unique device names**: Ensure no duplicate clip names in the "Devices" group
- **Color code tracks**: Use track colors for visual organization

---

# Using Multiple Extensions Together

The following sections cover usage of the MIDI Splitter and Step Recording Extension extensions, which can be used together with the Pattern Tracker for enhanced workflows.

## Usage

### Hardware Controller Functionality

Your hardware controller should maintain its full MIDI functionality through the original extension. LED feedback, display updates, and button responses work as normal.

**Note**: Some controllers use additional USB communication protocols beyond MIDI. These functions may not be available through the virtual MIDI routing.

### Adding Notes to Clips

With the Step Recording Extension:

1. **Select a clip** in Bitwig and open the piano roll editor
2. **Play notes** on your hardware keyboard
3. **Notes appear** at the current cursor position in the clip
4. **Cursor advances** automatically by the configured note length

### Cursor Movement

Configure MIDI learn for cursor navigation:

1. **Open Controller Preferences** for Step Recording Extension
2. **MIDI Learn section**:
   - Forward Button: Set to "Learning..." then press a hardware button
   - Backward Button: Set to "Learning..." then press a different button
3. **Use learned controls** to move the cursor position forward/backward

### Note Length Configuration

Adjust note length in **Studio I/O Panel → Step Recording Extension**:

- Range: 0.01 to 4.0 beats
- Default: 0.25 beats (16th note)

## Troubleshooting

### Original Extension Not Responding

- **Check**: MIDI Splitter input assigned to real hardware
- **Check**: Original extension input assigned to `Virtual-A-Original`
- **Restart**: Extensions in Bitwig Controller settings

### Step Recording Extension Not Working

- **Check**: Extension input assigned to `Virtual-B-Custom`
- **Check**: MIDI Splitter output 2 assigned to `Virtual-B-Custom`
- **Check**: Clip is selected and piano roll is open

### Virtual Ports Missing

- **Windows**: Restart LoopMIDI, then restart Bitwig
- **Verify**: Virtual ports are "active" in LoopMIDI
- **Restart**: Bitwig Studio completely

### MIDI Timing Issues

- **LoopMIDI**: Increase buffer size in settings
- **Bitwig**: Adjust audio buffer settings (Dashboard → Audio)

## Hardware-Specific Notes

### Ableton Push 2

- Most functions work through MIDI routing
- Some advanced features may require direct USB connection
- Consider using User mode for better compatibility

### Novation Launchpad

- Session mode: Original extension handles pad LEDs
- Note input: Works well with Step Recording Extension

## Extension Settings

### MIDI Splitter

- **Input Ports**: 1 (your hardware controller)
- **Output Ports**: 2 (virtual MIDI ports)

### Step Recording Extension

- **Note Length**: 0.01-4.0 beats (configurable)
- **Chord Detection**: 100ms threshold
- **Cursor Movement**: MIDI learnable

### Pattern Tracker

- **Track Configuration**: Configurable track and slot limits
- **Stop Keyword**: Customizable stop command keyword
- **Persistence**: Device playback behavior after pattern stop
- **Mapping**: Manual and automatic clip remapping

## Support

For issues:

1. **Check Bitwig logs**: Dashboard → Help → Open Log Folder
2. **Test virtual ports**: Use MIDI monitoring software
3. **Isolate problems**: Test extensions individually with direct hardware assignment

---

**Use your hardware controller with multiple Bitwig extensions simultaneously!** 🎹🎛️

---
