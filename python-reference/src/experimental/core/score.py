from fractions import Fraction
from dataclasses import dataclass, field
from typing import List, Tuple, Optional, Dict
from enum import IntEnum


class MidiEventType(IntEnum):
    NOTE_ON = 0x90
    NOTE_OFF = 0x80
    CONTROL_CHANGE = 0xB0
    PROGRAM_CHANGE = 0xC0
    PITCH_BEND = 0xE0


@dataclass(slots=True, frozen=True)
class Note:
    """A single note with exact fractional timing"""
    pitch: int  # MIDI note number (0-127)
    velocity: int  # 0-127
    start: Fraction  # Start time in beats
    duration: Fraction  # Duration in beats
    channel: int = 0  # MIDI channel (0-15)


@dataclass(slots=True, frozen=True)
class ControlEvent:
    """MIDI control change event"""
    controller: int  # Controller number (0-127)
    value: int  # Controller value (0-127)
    time: Fraction  # Event time in beats
    channel: int = 0


@dataclass(slots=True)
class Track:
    """A single MIDI track"""
    name: str = ""
    notes: List[Note] = field(default_factory=list)
    control_events: List[ControlEvent] = field(default_factory=list)
    program: Optional[int] = None  # Program change (instrument)

    def add_note(self, pitch: int, velocity: int, start: Fraction, duration: Fraction, channel: int = 0):
        self.notes.append(Note(pitch, velocity, start, duration, channel))
        return self

    def add_control(self, controller: int, value: int, time: Fraction, channel: int = 0):
        self.control_events.append(ControlEvent(controller, value, time, channel))
        return self


@dataclass
class Score:
    """Complete musical score"""
    tracks: List[Track] = field(default_factory=list)
    tempo: int = 120  # BPM (quarter notes per minute)
    time_signature: Tuple[int, int] = (4, 4)  # numerator, denominator
    key_signature: int = 0  # -7 to +7 (flats/sharps)

    def add_track(self, name: str = "") -> Track:
        track = Track(name)
        self.tracks.append(track)
        return track

    @property
    def duration(self) -> Fraction:
        """Total duration in beats"""
        if not self.tracks:
            return Fraction(0)
        max_end = Fraction(0)
        for track in self.tracks:
            for note in track.notes:
                end = note.start + note.duration
                if end > max_end:
                    max_end = end
        return max_end

    def get_notes_sorted(self) -> List[Note]:
        """All notes sorted by start time"""
        all_notes = []
        for track in self.tracks:
            all_notes.extend(track.notes)
        return sorted(all_notes, key=lambda n: (n.start, n.pitch))


# Example usage
def create_example_score() -> Score:
    score = Score(tempo=120)

    # Piano track
    piano = score.add_track("Piano")
    piano.program = 0  # Acoustic Grand Piano

    # C major scale, quarter notes
    for i, pitch in enumerate([60, 62, 64, 65, 67, 69, 71, 72]):
        piano.add_note(
            pitch=pitch,
            velocity=100,
            start=Fraction(i, 1),  # i beats
            duration=Fraction(1, 1)  # 1 beat (quarter note)
        )

    # Add a chord (half notes)
    piano.add_note(60, 80, Fraction(0, 1), Fraction(2, 1))  # C
    piano.add_note(64, 80, Fraction(0, 1), Fraction(2, 1))  # E
    piano.add_note(67, 80, Fraction(0, 1), Fraction(2, 1))  # G

    # Add a control change (sustain pedal)
    piano.add_control(64, 127, Fraction(0, 1))  # Pedal on
    piano.add_control(64, 0, Fraction(4, 1))  # Pedal off

    return score


# Create and inspect
if __name__ == "__main__":
    score = create_example_score()

    print(f"Tempo: {score.tempo} BPM")
    print(f"Time signature: {score.time_signature[0]}/{score.time_signature[1]}")
    print(f"Duration: {score.duration} beats")
    print(f"Total notes: {sum(len(t.notes) for t in score.tracks)}")

    print("\nFirst 5 notes:")
    for note in score.get_notes_sorted()[:5]:
        print(f" Pitch {note.pitch}: start={note.start} beat, "
              f"duration={note.duration} beat, channel={note.channel}")

    # Convert to absolute times (seconds) if needed
    seconds_per_beat = 60.0 / score.tempo
    print(f"\nSeconds per beat: {seconds_per_beat}")
    note = score.tracks[0].notes[0]
    print(f"Note 60 starts at {float(note.start) * seconds_per_beat:.2f}s")
