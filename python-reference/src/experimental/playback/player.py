import time
import threading
import mido
from mido import Message, open_output
from collections import defaultdict
import queue


class RealtimeMIDIPlayer:
    def __init__(self, midi_port_name=None, bpm=120):
        """
        Initialize the realtime MIDI player with mido

        Args:
            midi_port_name: Name of MIDI output port (None for virtual port)
            bpm: Beats per minute for timing
        """
        self.bpm = bpm
        self.notes_per_beat = 480  # ticks per quarter note
        self.tick_duration = 60.0 / (bpm * self.notes_per_beat)

        # MIDI setup with mido
        self.midi_out = None
        self._setup_midi_output(midi_port_name)

        # Note data structure
        self.notes = []  # Master list of all notes
        self.voices = defaultdict(list)  # Separate lists per voice/channel

        # Playing state
        self.is_playing = False
        self.play_thread = None
        self.stop_event = threading.Event()

        # Current position in beats
        self.current_beat = 0
        self.loop_start = 0
        self.loop_end = None

        # Active notes being held
        self.active_notes = set()

        # Note-off scheduler
        self.scheduler_running = True
        self.scheduler_thread = threading.Thread(target=self._scheduler_loop)
        self.scheduler_thread.daemon = True
        self.scheduler_thread.start()

        print(f"Player ready at {bpm} BPM")
        print(f"Tick duration: {self.tick_duration * 1000:.2f}ms")

    def _setup_midi_output(self, midi_port_name):
        """Setup MIDI output port using mido"""
        available_ports = mido.get_output_names()

        if midi_port_name:
            for port in available_ports:
                if midi_port_name in port:
                    try:
                        self.midi_out = open_output(port)
                        print(f"Opened MIDI port: {port}")
                        return
                    except:
                        pass
            print(f"Port {midi_port_name} not found, creating virtual port")

        # Try to open first available port or create virtual
        if available_ports and not midi_port_name:
            try:
                self.midi_out = open_output(available_ports[0])
                print(f"Opened first MIDI port: {available_ports[0]}")
                return
            except:
                pass

        # Create virtual port (platform dependent)
        try:
            # For macOS/Linux with ALSA or CoreMIDI
            self.midi_out = open_output('Realtime Player', virtual=True)
            print("Created virtual MIDI port: Realtime Player")
        except:
            # Fallback to first available
            if available_ports:
                self.midi_out = open_output(available_ports[0])
                print(f"Fallback to: {available_ports[0]}")
            else:
                raise Exception("No MIDI output ports available")

    def set_notes(self, notes, voice=0):
        """
        Set the notes for a specific voice

        Args:
            notes: List of note specifications
                  Simple: [(pitch, duration), ...]
                  With velocity: [(pitch, duration, velocity), ...]
                  Dictionaries: [{'pitch':60, 'duration':1, 'velocity':100, 'start':0}, ...]
                  Nested lists for polyphonic: [[note1, note2, ...], ...]
            voice: Voice/channel number (0-15)
        """
        parsed_notes = []
        current_time = 0

        # Handle polyphonic lists (lists within list)
        if notes and isinstance(notes[0], list):
            # This is polyphonic - multiple notes at same time points
            for time_slice in notes:
                if isinstance(time_slice, list):
                    for note in time_slice:
                        if isinstance(note, (list, tuple)):
                            pitch = note[0]
                            duration = note[1] if len(note) > 1 else 1
                            velocity = note[2] if len(note) > 2 else 100
                            parsed_notes.append((current_time, duration, pitch, velocity, voice))
                current_time += 1  # Move to next beat for next time slice
        else:
            # Monophonic or simple list
            for note in notes:
                if isinstance(note, (list, tuple)):
                    pitch = note[0]
                    duration = note[1] if len(note) > 1 else 1
                    velocity = note[2] if len(note) > 2 else 100
                    parsed_notes.append((current_time, duration, pitch, velocity, voice))
                    current_time += duration
                elif isinstance(note, dict):
                    start = note.get('start', current_time)
                    duration = note.get('duration', 1)
                    pitch = note.get('pitch', 60)
                    velocity = note.get('velocity', 100)
                    parsed_notes.append((start, duration, pitch, velocity, voice))
                    if 'start' not in note:
                        current_time = start + duration

        self.voices[voice] = parsed_notes
        self._rebuild_note_list()
        print(f"Set {len(parsed_notes)} notes for voice {voice}")

    def _rebuild_note_list(self):
        """Rebuild the master note list from all voices"""
        all_notes = []
        for voice_notes in self.voices.values():
            all_notes.extend(voice_notes)
        self.notes = sorted(all_notes, key=lambda x: x[0])  # Sort by start time

        # Update loop end if playing
        if self.is_playing and self.notes:
            last_end = max(start + duration for start, duration, _, _, _ in self.notes)
            self.loop_end = last_end

    def play(self, start_beat=0):
        """Start playing from the specified beat"""
        if self.is_playing:
            self.stop()
            time.sleep(0.05)  # Small delay to ensure clean stop

        self.is_playing = True
        self.stop_event.clear()
        self.current_beat = start_beat
        self.loop_start = start_beat

        # Find loop end (last note end)
        if self.notes:
            last_end = max(start + duration for start, duration, _, _, _ in self.notes)
            self.loop_end = last_end
        else:
            self.loop_end = 4  # Default 4 beats if no notes

        self.play_thread = threading.Thread(target=self._play_loop)
        self.play_thread.daemon = True
        self.play_thread.start()
        print(f"Playing from beat {start_beat} to {self.loop_end}")

    def stop(self):
        """Stop playing and turn off all notes"""
        if self.is_playing:
            self.is_playing = False
            self.stop_event.set()
            if self.play_thread:
                self.play_thread.join(timeout=1.0)

            # Turn off all active notes
            self._all_notes_off()

    def _all_notes_off(self):
        """Send all notes off to all channels"""
        for channel in range(16):
            self.midi_out.send(Message('control_change', channel=channel, control=123, value=0))
        self.active_notes.clear()

    def _play_loop(self):
        """Main playback loop - runs in separate thread"""
        while self.is_playing and not self.stop_event.is_set():
            start_time = time.time()

            # Schedule notes that should start at current_beat
            self._schedule_notes_at_beat(self.current_beat)

            # Move to next beat
            self.current_beat += 1

            # Loop handling
            if self.loop_end and self.current_beat >= self.loop_end:
                self.current_beat = self.loop_start

            # Calculate time until next beat
            next_beat_time = start_time + self.tick_duration * self.notes_per_beat

            # Sleep precisely until next beat
            sleep_time = next_beat_time - time.time()
            if sleep_time > 0:
                self.stop_event.wait(sleep_time)

    def _schedule_notes_at_beat(self, beat):
        """Schedule all notes that start at the given beat"""
        for start, duration, pitch, velocity, channel in self.notes:
            if abs(start - beat) < 0.001:  # Note starts at this beat
                # Send note on immediately
                self.midi_out.send(Message('note_on', channel=channel, note=pitch, velocity=velocity))
                self.active_notes.add((channel, pitch))

                # Schedule note off
                note_off_time = duration * self.tick_duration * self.notes_per_beat
                threading.Timer(
                    note_off_time,
                    self._send_note_off,
                    args=(channel, pitch)
                ).start()

    def _send_note_off(self, channel, pitch):
        """Send note off message"""
        if (channel, pitch) in self.active_notes:  # Only if note is still active
            self.midi_out.send(Message('note_off', channel=channel, note=pitch, velocity=0))
            self.active_notes.discard((channel, pitch))

    def _scheduler_loop(self):
        """Background thread for maintaining scheduler"""
        while self.scheduler_running:
            time.sleep(0.01)  # Just keep thread alive

    def align(self, start_beat=0, meter=(4, 4), phrase_length=4, swing=None):
        """
        Align the current note sequence to a meter

        Args:
            start_beat: Where to align the start (in beats)
            meter: Tuple of (beats_per_bar, beat_unit)
            phrase_length: Length of phrase in bars
            swing: Optional swing factor (0.0-1.0) to apply to offbeat notes
        """
        beats_per_bar = meter[0]
        total_beats = phrase_length * beats_per_bar

        # Quantize all note start times to the nearest beat division
        quantized_notes = []

        for start, duration, pitch, velocity, channel in self.notes:
            # Quantize start to nearest beat
            quantized_start = round(start)

            # Apply swing if requested (for offbeats)
            if swing is not None and quantized_start % 1 != 0:
                # This is an offbeat - apply swing
                beat_fraction = start - int(start)
                if beat_fraction == 0.5:  # Eighth note offbeat
                    quantized_start = int(start) + 0.5 + (swing * 0.5 - 0.25)

            quantized_notes.append((quantized_start + start_beat, duration, pitch, velocity, channel))

        # Update notes
        self.notes = quantized_notes
        self._rebuild_note_list()

        # Set loop points
        self.loop_start = start_beat
        self.loop_end = start_beat + total_beats

        print(f"Aligned notes to meter {meter[0]}/{meter[1]}, phrase: {phrase_length} bars")
        if swing:
            print(f"Applied swing factor: {swing}")
        print(f"Loop from {self.loop_start} to {self.loop_end}")

    def set_bpm(self, new_bpm):
        """Change tempo in realtime"""
        old_bpm = self.bpm
        self.bpm = new_bpm
        self.tick_duration = 60.0 / (self.bpm * self.notes_per_beat)

        # If playing, adjust timing
        if self.is_playing:
            # This is a simplification - real tempo change would need more sophisticated handling
            print(f"Tempo changed from {old_bpm} to {new_bpm} BPM (will take effect next loop)")
        else:
            print(f"Tempo changed to {new_bpm} BPM")

    def get_notes(self, voice=None):
        """Get current notes for debugging"""
        if voice is not None:
            return self.voices.get(voice, [])
        return self.notes

    def panic(self):
        """Immediately stop all notes (MIDI panic)"""
        self._all_notes_off()
        print("MIDI Panic - all notes off")

    def close(self):
        """Clean shutdown"""
        self.stop()
        self.panic()
        self.scheduler_running = False
        if self.scheduler_thread:
            self.scheduler_thread.join(timeout=1.0)
        if self.midi_out:
            self.midi_out.close()
        print("Player closed")


# Interactive usage example
if __name__ == "__main__":
    # Create player
    player = RealtimeMIDIPlayer(bpm=120)

    print("\n" + "=" * 50)
    print("Interactive MIDI Player Ready (mido version)")
    print("=" * 50)
    print("\nCommands you can run in the interpreter:")
    print("\n-- Basic monophonic sequences --")
    print('  player.set_notes([(60,1), (62,1), (64,2)])  # Simple pitches with durations')
    print('  player.set_notes([(60,1,100), (62,1,100)])  # With velocity')
    print('  player.set_notes([{"pitch":60,"duration":1},{"pitch":64,"duration":2}])  # Dict format')

    print("\n-- Multiple voices (polyphonic) --")
    print('  player.set_notes([(60,2)], voice=0)  # Voice 0 (channel 0)')
    print('  player.set_notes([(64,2)], voice=1)  # Voice 1 (channel 1)')

    print("\n-- Polyphonic (chords) --")
    print('  player.set_notes([[(60,1),(64,1),(67,1)], [(62,1),(65,1),(69,1)]])')

    print("\n-- Control commands --")
    print('  player.play()                    # Start playing')
    print('  player.play(start_beat=2)        # Start from beat 2')
    print('  player.stop()                     # Stop playing')
    print('  player.panic()                     # Emergency stop')
    print('  player.align(start_beat=0, meter=(4,4), phrase_length=2)  # Align to meter')
    print('  player.align(swing=0.6)           # Add swing feel')
    print('  player.set_bpm(140)                # Change tempo')
    print('  player.get_notes()                  # See current notes')
    print('  player.close()                      # Clean shutdown')

    print("\nExample: Play a simple melody")
    print('  player.set_notes([(60,0.5), (62,0.5), (64,0.5), (65,1), (67,1), (69,2)])')
    print('  player.play()')

    print("\n" + "=" * 50)
    print("Starting interactive shell...")
    print("=" * 50 + "\n")

    # Start interactive shell
    import code

    code.interact(local=locals())