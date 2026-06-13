#!/usr/bin/env python3
"""
MIDI Synthesizer Instrument and Drum Sound Tester
Requires: mido, python-rtmidi
"""

import mido
import time
import sys
from typing import List, Optional


def list_midi_output_ports() -> List[str]:
    """List all available MIDI output ports."""
    outputs = mido.get_output_names()
    print("\nAvailable MIDI Output Ports:")
    for i, port in enumerate(outputs):
        print(f"{i}: {port}")
    return outputs


def select_midi_port(ports: List[str]) -> Optional[str]:
    """Let user select a MIDI port or return None if no ports available."""
    if not ports:
        print("No MIDI output ports found!")
        return None

    if len(ports) == 1:
        print(f"\nAutomatically selected: {ports[0]}")
        return ports[0]

    while True:
        try:
            choice = input("\nSelect port number (or 'q' to quit): ")
            if choice.lower() == 'q':
                return None
            idx = int(choice)
            if 0 <= idx < len(ports):
                return ports[idx]
            print(f"Please enter a number between 0 and {len(ports) - 1}")
        except ValueError:
            print("Invalid input. Please enter a number or 'q'")


def send_program_change(outport: mido.ports.IOPort, program: int, channel: int = 0):
    """Send program change message."""
    msg = mido.Message('program_change', program=program, channel=channel)
    outport.send(msg)
    time.sleep(0.01)  # Small delay for program change to take effect


def play_note(outport: mido.ports.IOPort, note: int, velocity: int = 100,
              duration: float = 0.3, channel: int = 0):
    """Play a single MIDI note."""
    note_on = mido.Message('note_on', note=note, velocity=velocity, channel=channel)
    note_off = mido.Message('note_off', note=note, velocity=velocity, channel=channel)

    outport.send(note_on)
    time.sleep(duration)
    outport.send(note_off)
    time.sleep(0.1)  # Gap between notes


def test_midi_instruments(outport: mido.ports.IOPort):
    """Test all General MIDI instruments (programs 0-127)."""
    print("\n" + "=" * 60)
    print("TESTING MIDI INSTRUMENTS (Program Numbers 0-127)")
    print("=" * 60)

    # General MIDI instrument names
    instrument_families = {
        "Piano": list(range(0, 8)),
        "Chromatic Percussion": list(range(8, 16)),
        "Organ": list(range(16, 24)),
        "Guitar": list(range(24, 32)),
        "Bass": list(range(32, 40)),
        "Strings": list(range(40, 48)),
        "Ensemble": list(range(48, 56)),
        "Brass": list(range(56, 64)),
        "Reed": list(range(64, 72)),
        "Pipe": list(range(72, 80)),
        "Synth Lead": list(range(80, 88)),
        "Synth Pad": list(range(88, 96)),
        "Synth Effects": list(range(96, 104)),
        "Ethnic": list(range(104, 112)),
        "Percussive": list(range(112, 120)),
        "Sound Effects": list(range(120, 128))
    }

    # Instrument names (simplified GM names)
    instrument_names = [
        "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
        "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
        "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
        "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
        "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
        "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
        "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar harmonics",
        "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        "Violin", "Viola", "Cello", "Contrabass",
        "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
        "String Ensemble 1", "String Ensemble 2", "SynthStrings 1", "SynthStrings 2",
        "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
        "French Horn", "Brass Section", "SynthBrass 1", "SynthBrass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
        "Oboe", "English Horn", "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute",
        "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
        "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
        "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass+lead)",
        "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
        "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
        "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
        "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
        "Sitar", "Banjo", "Shamisen", "Koto",
        "Kalimba", "Bag pipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
        "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
        "Telephone Ring", "Helicopter", "Applause", "Gunshot"
    ]

    print("\nPress Ctrl+C to stop testing at any time")
    input("\nPress Enter to start testing instruments...")

    try:
        for family, program_range in instrument_families.items():
            print(f"\n--- {family} ---")

            for program in program_range:
                instrument_name = instrument_names[program] if program < len(instrument_names) else f"Program {program}"
                print(f"  Program {program:3d}: {instrument_name}")

                # Select instrument
                send_program_change(outport, program)
                time.sleep(0.05)

                # Play a short melody (C major triad)
                play_note(outport, 60, duration=0.2)  # C4
                play_note(outport, 64, duration=0.2)  # E4
                play_note(outport, 67, duration=0.2)  # G4
                play_note(outport, 72, duration=0.4)  # C5

                time.sleep(0.3)

    except KeyboardInterrupt:
        print("\n\nInstrument testing stopped by user")


def test_drum_sounds(outport: mido.ports.IOPort):
    """Test drum sounds (channel 10, notes 35-81)."""
    print("\n" + "=" * 60)
    print("TESTING DRUM SOUNDS (Channel 10, Notes 35-81)")
    print("=" * 60)

    # Common drum sound names (General MIDI drum map)
    drum_names = {
        35: "Acoustic Bass Drum",
        36: "Bass Drum 1",
        37: "Side Stick",
        38: "Acoustic Snare",
        39: "Hand Clap",
        40: "Electric Snare",
        41: "Low Floor Tom",
        42: "Closed Hi-Hat",
        43: "High Floor Tom",
        44: "Pedal Hi-Hat",
        45: "Low Tom",
        46: "Open Hi-Hat",
        47: "Low-Mid Tom",
        48: "Hi-Mid Tom",
        49: "Crash Cymbal 1",
        50: "High Tom",
        51: "Ride Cymbal 1",
        52: "Chinese Cymbal",
        53: "Ride Bell",
        54: "Tambourine",
        55: "Splash Cymbal",
        56: "Cowbell",
        57: "Crash Cymbal 2",
        58: "Vibraslap",
        59: "Ride Cymbal 2",
        60: "Hi Bongo",
        61: "Low Bongo",
        62: "Mute Hi Conga",
        63: "Open Hi Conga",
        64: "Low Conga",
        65: "High Timbale",
        66: "Low Timbale",
        67: "High Agogo",
        68: "Low Agogo",
        69: "Cabasa",
        70: "Maracas",
        71: "Short Whistle",
        72: "Long Whistle",
        73: "Short Guiro",
        74: "Long Guiro",
        75: "Claves",
        76: "Hi Wood Block",
        77: "Low Wood Block",
        78: "Mute Cuica",
        79: "Open Cuica",
        80: "Mute Triangle",
        81: "Open Triangle"
    }

    print("\nPress Ctrl+C to stop testing at any time")
    input("\nPress Enter to start testing drum sounds...")

    try:
        # Set channel 10 (drums) - program change not needed for drums
        # But we'll send a quick reset to ensure we're on channel 10
        outport.send(mido.Message('program_change', program=0, channel=9))  # Channel 10 = index 9

        print("\n--- Drum Sounds ---")

        # Test each drum note
        for note in range(35, 82):  # Standard GM drum range
            drum_name = drum_names.get(note, f"Note {note}")
            print(f"  Note {note:3d}: {drum_name}")

            # Play drum sound
            play_note(outport, note, velocity=100, duration=0.2, channel=9)  # Channel 10 = index 9

            # Play a short drum pattern for certain notes
            if note in [36, 38, 42, 46, 49, 51]:  # Bass, snare, hats, cymbals
                print("    (playing pattern: ", end="", flush=True)
                for i in range(4):
                    play_note(outport, note, velocity=80 + i * 10, duration=0.1, channel=9)
                    print(".", end="", flush=True)
                print(")")

            time.sleep(0.2)

    except KeyboardInterrupt:
        print("\n\nDrum testing stopped by user")


def test_drum_pattern(outport: mido.ports.IOPort):
    """Play a simple drum pattern."""
    print("\n" + "=" * 60)
    print("PLAYING DEMO DRUM PATTERN")
    print("=" * 60)

    try:
        # Simple rock beat
        pattern = [
            (36, 0.0),  # Bass drum on beat 1
            (38, 0.5),  # Snare on beat 2
            (36, 1.0),  # Bass drum on beat 3
            (38, 1.5),  # Snare on beat 4
            (42, 0.25),  # Hi-hat on offbeats
            (42, 0.75),
            (42, 1.25),
            (42, 1.75),
        ]

        print("\nPlaying 4 bars of rock beat...")
        for bar in range(4):
            print(f"Bar {bar + 1}: ", end="", flush=True)
            for note, beat_time in pattern:
                # Convert beat time to actual time with tempo
                play_note(outport, note, velocity=90, duration=0.1, channel=9)
                time.sleep(0.2)  # 120 BPM = 0.5s per beat, so 0.2s is fine
            print("✓")

    except KeyboardInterrupt:
        print("\n\nPattern stopped by user")


def main():
    """Main function to run the MIDI synthesizer tester."""
    print("=" * 60)
    print("MIDI SYNTHESIZER INSTRUMENT AND DRUM TESTER")
    print("=" * 60)
    print(f"Python version: {sys.version}")
    print(f"mido version: {mido.version}")

    # List available MIDI ports
    ports = list_midi_output_ports()

    # Select MIDI port
    selected_port = select_midi_port(ports)
    if not selected_port:
        print("Exiting...")
        return

    # Open the MIDI output port
    try:
        with mido.open_output(selected_port) as outport:
            print(f"\nConnected to: {selected_port}")
            print("Make sure your synthesizer is connected and listening!")

            while True:
                print("\n" + "=" * 60)
                print("MENU")
                print("=" * 60)
                print("1. Test all MIDI instruments")
                print("2. Test all drum sounds")
                print("3. Play demo drum pattern")
                print("4. List MIDI ports again")
                print("5. Exit")

                choice = input("\nSelect option (1-5): ").strip()

                if choice == '1':
                    test_midi_instruments(outport)
                elif choice == '2':
                    test_drum_sounds(outport)
                elif choice == '3':
                    test_drum_pattern(outport)
                elif choice == '4':
                    ports = list_midi_output_ports()
                elif choice == '5':
                    print("Exiting...")
                    break
                else:
                    print("Invalid option. Please try again.")

    except KeyboardInterrupt:
        print("\n\nExiting...")
    except Exception as e:
        print(f"\nError: {e}")
        print("Make sure your MIDI device is connected and not in use by another application.")


if __name__ == "__main__":
    main()