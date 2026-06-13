# src/common/data/pitch.py
"""
Pitch reference data — note-name arrays, interval ratios, tuning, pitch bend.

Pure list/dict literals. No classes, no functions, no mutation.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Optional

# ── Note-name → pitch-class index (chromatic, one octave) ─────

NOTE_NAMES_SHARP = ["c", "c#", "d", "d#", "e", "f",
                    "f#", "g", "g#", "a", "a#", "b"]

NOTE_NAMES_FLAT = ["c", "db", "d", "eb", "e", "f",
                   "gb", "g", "ab", "a", "bb", "b"]

NOTE_NAME_TO_VALUE = {
    "c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11,
}

# ── Pitch-class → display name (uppercase, for Key-aware spelling) ──

PITCH_CLASS_SHARP = ["C", "C#", "D", "D#", "E", "F",
                     "F#", "G", "G#", "A", "A#", "B"]

PITCH_CLASS_FLAT  = ["C", "Db", "D", "Eb", "E", "F",
                     "Gb", "G", "Ab", "A", "Bb", "B"]

# ═══════════════════════════════════════════════════════════════
# Tuning Standards
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class TuningValue:
    """Tuning standard with frequency in Hz."""
    frequency: float
    name: str


class Tuning(Enum):
    """Standard tuning references."""
    a4 = TuningValue(440.0, "A4 = 440 Hz")
    baroque_a4 = TuningValue(415.0, "Baroque A4 = 415 Hz")
    classical_a4 = TuningValue(430.0, "Classical A4 = 430 Hz")
    devine_nine = TuningValue(432.0, "Devine Nine A4 = 432 Hz")
    modern_a4 = TuningValue(442.0, "Modern A4 = 442 Hz")

    @property
    def frequency(self) -> float:
        return self.value.frequency

    @classmethod
    def get(cls, name: str) -> Optional['Tuning']:
        """Get tuning by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    @classmethod
    def get_closest(cls, frequency: float) -> Optional['Tuning']:
        """Get closest tuning standard to given frequency."""
        return min(cls, key=lambda t: abs(t.frequency - frequency))


# ═══════════════════════════════════════════════════════════════
# Just Intonation Intervals
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class IntervalValue:
    """Just intonation interval ratio."""
    ratio: float
    cents: float  # Cents from equal temperament

    def __post_init__(self):
        # Calculate cents if not provided
        if self.cents == 0.0 and self.ratio != 1.0:
            import math
            object.__setattr__(self, 'cents', 1200 * math.log2(self.ratio))


class JustInterval(Enum):
    """Just intonation intervals with ratios."""
    unison = IntervalValue(1 / 1, 0)
    minor_second = IntervalValue(16 / 15, 0)
    major_second = IntervalValue(9 / 8, 0)
    minor_third = IntervalValue(6 / 5, 0)
    major_third = IntervalValue(5 / 4, 0)
    perfect_fourth = IntervalValue(4 / 3, 0)
    augmented_fourth = IntervalValue(45 / 32, 0)
    perfect_fifth = IntervalValue(3 / 2, 0)
    minor_sixth = IntervalValue(8 / 5, 0)
    major_sixth = IntervalValue(5 / 3, 0)
    minor_seventh = IntervalValue(9 / 5, 0)
    major_seventh = IntervalValue(15 / 8, 0)
    octave = IntervalValue(2 / 1, 0)

    @property
    def ratio(self) -> float:
        return self.value.ratio

    @property
    def cents(self) -> float:
        return self.value.cents

    @classmethod
    def get(cls, name: str) -> Optional['JustInterval']:
        """Get interval by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    def apply(self, frequency: float) -> float:
        """Apply interval ratio to a frequency."""
        return frequency * self.ratio


# ═══════════════════════════════════════════════════════════════
# MIDI Pitch Bend Range
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class PitchBendRange:
    """MIDI pitch bend range."""
    min: int = -8192
    max: int = 8191
    center: int = 0
    semitone_range: int = 2

    @property
    def total_range(self) -> int:
        """Total range in steps."""
        return self.max - self.min

    @property
    def cents_per_step(self) -> float:
        """Cents per pitch bend step."""
        return (self.semitone_range * 100) / self.total_range

    def bend_to_pitch_factor(self, bend_value: int) -> float:
        """Convert pitch bend value to pitch factor (1.0 = no bend)."""
        if bend_value == self.center:
            return 1.0
        semitones = (bend_value - self.center) / self.total_range * self.semitone_range
        return 2 ** (semitones / 12)

    def pitch_to_bend(self, semitone_shift: float) -> int:
        """Convert semitone shift to pitch bend value."""
        if -self.semitone_range <= semitone_shift <= self.semitone_range:
            normalized = semitone_shift / self.semitone_range
            return self.center + int(normalized * self.total_range / 2)
        return self.max if semitone_shift > 0 else self.min

    def clamp(self, value: int) -> int:
        """Clamp pitch bend value to valid range."""
        return max(self.min, min(self.max, value))


# Singleton instance
PITCH_BEND = PitchBendRange()


# ═══════════════════════════════════════════════════════════════
# Convenience lookup functions (backward compatible)
# ═══════════════════════════════════════════════════════════════

def get_tuning(name: str) -> Optional[Tuning]:
    """Get tuning by name."""
    return Tuning.get(name)


def get_tuning_frequency(name: str) -> float:
    """Get frequency for tuning standard."""
    tuning = Tuning.get(name)
    return tuning.frequency if tuning else 440.0


def get_interval(name: str) -> Optional[JustInterval]:
    """Get interval by name."""
    return JustInterval.get(name)


def get_interval_ratio(name: str) -> float:
    """Get ratio for interval."""
    interval = JustInterval.get(name)
    return interval.ratio if interval else 1.0
