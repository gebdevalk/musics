import time
import threading
import queue
from collections import defaultdict
import rtmidi
from rtmidi.midiconstants import NOTE_ON, NOTE_OFF, CONTROL_CHANGE
import numpy as np


class RealtimeMIDIPlayer:
    def __init__(self, midi_port_name=None, bpm=120):
        """
        Initialize the realtime MIDI player

        Args:
            midi_port_name: Name of MIDI output port (None for virtual port)
            bpm: Beats per minute for timing
        """
        self.bpm = bpm
        self.notes_per_beat = 480  # ticks per quarter note
        self.tick_duration = 60.0 / (bpm * self.notes_per_beat)

        # MIDI setup
        self.midi_out = rtmidi.MidiOut()
        available_ports = self.midi_out.get_ports()

        if midi_port_name:
            for i, port in enumerate(available_ports):
                if midi_port_name in port:
                    self.midi_out.open_port(i)
                    print(f"Opened MIDI port: {port}")
                    break
            else:
                print(f"Port {midi_port_name} not found, creating virtual port")
                self.midi_out.open_virtual_port("Realtime Player")
        else:
            if available_ports:
                self.midi_out.open_port(0)
                print(f"Opened first MIDI port: {available_ports[0]}")
            else:
                self.midi_out.open_virtual_port("Realtime Player")
                print("Created virtual MIDI port: Realtime Player")

        # Note data structure - list of notes with timing info
        # Each note: (start_time, duration, pitch, velocity, channel)
        self.notes = []
        self.voices = defaultdict(list)  # Separate lists per voice/channel

        # Playing state
        self.is_playing = False
        self.play_thread = None
        self.stop_event = threading.Event()
        self.scheduled_notes = queue.Queue()

        # Current position in beats (0 = start of current sequence)
        self.current_beat = 0
        self.loop_start = 0
        self.loop_end = None

        # Active notes being held
        self.active_notes = set()

        # Start scheduler thread
        self.scheduler_running = True
        self.scheduler_thread = threading.Thread(target=self._scheduler_loop)
        self.scheduler_thread.daemon = True
        self.scheduler_thread.start()

        print(f"Player ready at {bpm} BPM")
        print(f"Tick duration: {self.tick_duration * 1000:.2f}ms")

    def set_notes(self, notes, voice=0):
        """
        Set the notes for a specific voice

        Args:
            notes: List of note tuples (pitch, duration, velocity) or 
                   List of lists for polyphonic: [[pitch1, duration1, vel1], [pitch2, duration2, vel2], ...]
                   Or list of dictionaries: [{'pitch': 60, 'duration': 1, 'velocity': 100, 'start': 0}, ...]
            voice: Voice/channel number (0-15)
        """
        parsed_notes = []
        current_time = 0

        for note in notes:
            if isinstance(note, (list, tuple)):
                if len(note) >= 2:
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
            self.midi_out.send_message([CONTROL_CHANGE | channel, 123, 0])
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
                # Schedule note on
                note_on_time = time.time()
                note_off_time = note_on_time + (duration * self.tick_duration * self.notes_per_beat)

                # Send note on immediately
                self.midi_out.send_message([NOTE_ON | channel, pitch, velocity])
                self.active_notes.add((channel, pitch))

                # Schedule note off
                threading.Timer(
                    duration * self.tick_duration * self.notes_per_beat,
                    self._send_note_off,
                    args=(channel, pitch)
                ).start()

    def _send_note_off(self, channel, pitch):
        """Send note off message"""
        self.midi_out.send_message([NOTE_OFF | channel, pitch, 0])
        self.active_notes.discard((channel, pitch))

    def _scheduler_loop(self):
        """Background thread for scheduled note-offs"""
        while self.scheduler_running:
            try:
                # This thread just keeps running to handle any timer-based operations
                time.sleep(0.01)
            except:
                break

    def align(self, start_beat=0, meter=(4, 4), phrase_length=4):
        """
        Align the current note sequence to a meter

        Args:
            start_beat: Where to align the start (in beats)
            meter: Tuple of (beats_per_bar, beat_unit)
            phrase_length: Length of phrase in bars
        """
        beats_per_bar = meter[0]
        total_beats = phrase_length * beats_per_bar

        # Quantize all note start times to the nearest beat division
        quantized_notes = []
        for start, duration, pitch, velocity, channel in self.notes:
            # Quantize start to nearest beat
            quantized_start = round(start)
            quantized_notes.append((quantized_start + start_beat, duration, pitch, velocity, channel))

        # Update notes
        self.notes = quantized_notes
        self._rebuild_note_list()

        # Set loop points
        self.loop_start = start_beat
        self.loop_end = start_beat + total_beats

        print(f"Aligned notes to meter {meter[0]}/{meter[1]}, phrase: {phrase_length} bars")
        print(f"Loop from {self.loop_start} to {self.loop_end}")

    def set_bpm(self, new_bpm):
        """Change tempo in realtime"""
        self.bpm = new_bpm
        self.tick_duration = 60.0 / (self.bpm * self.notes_per_beat)
        print(f"Tempo changed to {new_bpm} BPM")

    def get_notes(self, voice=None):
        """Get current notes for debugging"""
        if voice is not None:
            return self.voices.get(voice, [])
        return self.notes

    def close(self):
        """Clean shutdown"""
        self.stop()
        self.scheduler_running = False
        if self.scheduler_thread:
            self.scheduler_thread.join(timeout=1.0)
        self.midi_out.close_port()


# Interactive example usage
if __name__ == "__main__":
    # Create player
    player = RealtimeMIDIPlayer(bpm=120)

    print("\n=== Interactive MIDI Player Ready ===")
    print("Commands you can run in the interpreter:")
    print(" player.set_notes([(60,1,100), (62,1,100), (64,1,100)]) # Monophonic")
    print(" player.set_notes([(60,1), (62,1), (64,2)], voice=0) # With default velocity")
    print(" player.set_notes([{'pitch':60,'duration':1},{'pitch':64,'duration':2}], voice=1)")
    print(" player.play() # Start playing")
    print(" player.play(start_beat=2) # Start from beat 2")
    print(" player.stop() # Stop playing")
    print(" player.align(start_beat=0, meter=(4,4), phrase_length=2) # Align to meter")
    print(" player.set_bpm(140) # Change tempo")
    print(" player.get_notes() # See current notes")
    print(" player.close() # Clean shutdown")
    print("\nExample: Play a simple C major scale")

    # Interactive shell - this lets you use the player object in the interpreter
    import code

    code.interact(local=locals())
