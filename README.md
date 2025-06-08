# Bitwig Studio Extensions

This project contains three Bitwig Studio controller extensions. Some of it was written with Cursor.
Currently requires the latest API version. Copy the release files with `.bwextension` to
`c:\Users\<your-user>\Documents\Bitwig Studio\Extensions` and add the extensions to your running
Bitwig in the settings -> controllers section

## 1. Pattern Tracker

Create music similar to trackers from the 90s by having patterns and having pattern triggers.

**Core Concept**: Empty trigger clips in a "Patterns" group automatically launch corresponding content clips in a "Devices" group when their names match.

### Features

- **Name-Based Triggering**: Pattern clips (usually empty) trigger device clips with matching names
- **Transport-Aware Operations**: Device clips only trigger when transport is playing; stop when transport stops
- **Stop Commands**: Use `[stop] TrackName` to stop specific device tracks
- **Continue/Stop playing pattern**: Choose whether device clips continue playing after pattern clips end
- **Remap Button**: Triggers reload of mapping between pattern and device clips - this is a workaround for until a better solution is found
- **Error Handling**: Missing clips or invalid mappings are ignored

### Guide

The Pattern Tracker extension allows a 90s tracker-like experience using Bitwig's clip launcher

#### Required Track Setup

The extension requires two specific **Group Tracks** in your Bitwig project:

#### 1. "Devices" Group Track

Create a Group Track named exactly **"Devices"** containing:

- **Device/Instrument tracks** with the actual musical content
- **Content clips** with note and/or audio clips (e.g., "Intro", "Verse A", "Chorus", "Bridge")
- **Audio clips**, **note clips** with working device chains that make up your patterns
- **Unique names required**: No two clips should have identical names to avoid mapping conflicts
- **Nested groups supported**: You can organize tracks into subgroups for better organization

#### 2. "Patterns" Group Track

Create a Group Track named exactly **"Patterns"** containing:

- **Child tracks** with **pattern clips** that reference device clips from the **Devices** group by name
- **Stop command clips** named `[stop] TrackName` (e.g., `[stop] Bass`, `[stop] Drums`) This immediately stops the track with the name after the command (it is not possible to stop clips with the API)
- **Nested groups supported**: You can organize trigger tracks into subgroups for better organization
- **Real-time Updates**: Automatic remapping when clip names change or clips are moved around

#### 3. Extension Configuration

1. **Configure Settings** (in preferences):

   - **Number of Tracks per Group**:
     - Default 5
     - How many tracks are "seen" by this extension within the **Devices** and the **Patterns** group
     - the higher the more resources this may drain from your system
   - **Number of root Groups**: Default 2 (Patterns + Devices groups) - if the first two groups are the aforementioned groups, you don't need more than the value 2 here.
   - **Number of Slots per Track**:
     - Default is 5
     - How many slots in the clip launcher are "seen" by this extension within the **Devices** and the **Patterns** group
     - the higher the more resources this may drain from your system
   - **Stop Keyword**: Default `[stop]` (customize if you want to use a different word)
   - It is unknown if these settings could just be fixed to maximum values due to possible performance considerations.
     The extension needs to observe changes in your project and remap automatically.

2. **Initial Mapping**:
   - Press **"Remap Clips"** button in extension settings after loading your project. This is required and serves as a workaround for limitations of the Bitwig API.

## 2. Step Recording Extension

Step recording with various step lengths

- **Toggle: Enable/disable**: arm or disarm this extension
- **Disabled when switching to a different clip**: disables itself automatically when opening a different clip.
- **Step Recording**: Play notes on your MIDI keyboard to add them to the currently selected clip in the clip launcher
- **Musical Note Values**: Standard note values from 32/1 down to 1/64
- **Triplet Support**: Regular and triplet timing for all note values
- **Chord Detection**: 100ms threshold for chord recognition to enter chords at the current position
- **MIDI Learn Navigation**: CC-based cursor movement controls
- **Toggle: clear old notes**: New notes at the same position replace existing ones or don't depending on the toggle setting
- **Toggle: clear while moving cursor**: delete notes when moving forward or backward, allows to insert gaps or delete mistakes
- **Button. clear notes under cursor**: deletes notes at the current position
- **Sleep when transport is playing**: is temporarily disabled when transport is playing
- **MIDI learn for all features**: go to preferences of controller extension and click on
  "Learning..." button and send CC values. When learning was successful, neither "Learning..." nor
  "Not Mapped" appear clicked.

## 3. MIDI Splitter

Enables using one hardware MIDI controller with multiple Bitwig extensions simultaneously:

- **MIDI Routing**: Receives MIDI input from one hardware device and forwards to multiple outputs (LoopMIDI or other virtual MIDI ports are recommended for this)
- **Multi-Extension Support**: Use your controller with original manufacturer extension plus custom extensions like the step recording extension
- **Complete MIDI Support**: Forwards all MIDI message types including Note On/Off, CC, and SysEx
- **Simultaneous Operation**: Hardware controller functions normally while custom extensions receive input

### Setup Guide

The MIDI Splitter is the key to using multiple extensions with one controller. Here's how to set it up:

#### What You Need

- **Windows**: [LoopMIDI](https://www.tobias-erichsen.de/software/loopmidi.html) (free)
- **Mac**: unknown
- **Linux**: unknown

#### Setup Overview

```
Hardware Controller (e.g., Akai MPK mini plus)
          ↓
    MIDI Splitter Extension
          ↓
    ┌─────────────┐
    ↓             ↓
Virtual Port A  Virtual Port B
    ↓             ↓
Original        Second
Hardware        Extension (e.g. step recorder from above)
Extension
```

## Development

- Install sdkman on WSL2 (https://sdkman.io/install)
- `sdk install java`
- `sdk install maven`
- `sdk install mvnd`
- Copy .env.dist to .env and replace `<user>` with your real user.
- run `./install.sh`
