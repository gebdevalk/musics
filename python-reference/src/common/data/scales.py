from dataclasses import dataclass
from enum import Enum
from typing import Optional, List, Tuple, Dict
from fractions import Fraction

# Import Key from the keys module
from common.data.keys import Key


# ═══════════════════════════════════════════════════════════════
# Scale Value
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class ScaleValue:
    """Musical scale information."""
    offset: int  # Offset from key tonic (in semitones)
    intervals: List[int]  # Scale intervals in semitones from root
    display_name: str  # Display name
    alternative_names: Tuple[str, ...] = ()  # Other names for this scale

    def __post_init__(self):
        """Validate scale data."""
        if not self.intervals:
            raise ValueError("Scale must have at least one interval")
        if self.intervals[0] != 0:
            raise ValueError("Scale intervals must start with 0")

    @property
    def num_notes(self) -> int:
        """Number of notes in scale."""
        return len(self.intervals)

    @property
    def is_heptatonic(self) -> bool:
        """Whether scale has 7 notes."""
        return self.num_notes == 7

    @property
    def is_pentatonic(self) -> bool:
        """Whether scale has 5 notes."""
        return self.num_notes == 5

    @property
    def is_octatonic(self) -> bool:
        """Whether scale has 8 notes."""
        return self.num_notes == 8

    @property
    def is_hexatonic(self) -> bool:
        """Whether scale has 6 notes."""
        return self.num_notes == 6

    @property
    def range(self) -> int:
        """Total range of scale in semitones (max interval)."""
        return max(self.intervals)

    @property
    def steps(self) -> List[int]:
        """Get step sizes between consecutive notes."""
        steps = []
        for i in range(len(self.intervals) - 1):
            steps.append(self.intervals[i + 1] - self.intervals[i])
        # Add step from last note to octave
        steps.append(12 - self.intervals[-1])
        return steps

    def transpose(self, semitones: int) -> List[int]:
        """Transpose scale intervals by semitones."""
        return [(i + semitones) % 12 for i in self.intervals]

    def contains_note(self, pitch_class: int, root: int = 0) -> bool:
        """Check if pitch class is in scale."""
        relative_pc = (pitch_class - root) % 12
        return relative_pc in self.intervals


# ═══════════════════════════════════════════════════════════════
# Scale Enum
# ═══════════════════════════════════════════════════════════════

class Scale(Enum):
    """Musical scales with intervals and metadata."""

    # Major/Minor
    major = ScaleValue(0, [0, 2, 4, 5, 7, 9, 11], "Major", ("ionian",))
    minor = ScaleValue(-3, [0, 2, 3, 5, 7, 9, 10], "Minor", ("natural_minor", "aeolian"))
    harmonic_minor = ScaleValue(-3, [0, 2, 3, 5, 7, 8, 11], "Harmonic Minor")
    melodic_minor = ScaleValue(-3, [0, 2, 3, 5, 7, 9, 11], "Melodic Minor")

    # Modes
    ionian = ScaleValue(0, [0, 2, 4, 5, 7, 9, 11], "Ionian", ("major",))
    aeolian = ScaleValue(-3, [0, 2, 3, 5, 7, 9, 10], "Aeolian", ("minor", "natural_minor"))
    dorian = ScaleValue(2, [0, 2, 3, 5, 7, 9, 10], "Dorian")
    mixolydian = ScaleValue(7, [0, 2, 4, 5, 7, 9, 10], "Mixolydian")
    phrygian = ScaleValue(4, [0, 1, 3, 5, 7, 8, 10], "Phrygian")
    lydian = ScaleValue(5, [0, 2, 4, 6, 7, 9, 11], "Lydian")
    locrian = ScaleValue(-1, [0, 1, 3, 5, 6, 8, 10], "Locrian")

    # Hypo modes
    hypoionian = ScaleValue(7, [0, 2, 4, 6, 7, 9, 11], "Hypoionian")
    hypodorian = ScaleValue(-3, [0, 2, 4, 5, 7, 9, 10], "Hypodorian")
    hypophrygian = ScaleValue(-1, [0, 2, 3, 5, 7, 8, 10], "Hypophrygian")
    hypolydian = ScaleValue(0, [0, 1, 3, 5, 6, 8, 10], "Hypolydian")
    hypomixolydian = ScaleValue(2, [0, 2, 3, 5, 7, 9, 10], "Hypomixolydian")
    hypoaeolian = ScaleValue(4, [0, 1, 3, 5, 7, 8, 10], "Hypoaeolian")
    hypolocrian = ScaleValue(5, [0, 2, 3, 5, 7, 8, 10], "Hypolocrian")

    # Pentatonic
    pentatonic_major = ScaleValue(0, [0, 2, 4, 7, 9], "Major Pentatonic")
    pentatonic_minor = ScaleValue(0, [0, 3, 5, 7, 10], "Minor Pentatonic")

    # Blues
    blues_major = ScaleValue(0, [0, 2, 3, 4, 7, 9], "Major Blues")
    blues_minor = ScaleValue(0, [0, 3, 5, 6, 7, 10], "Minor Blues")

    # Symmetric
    whole_tone = ScaleValue(0, [0, 2, 4, 6, 8, 10], "Whole Tone")
    diminished_hw = ScaleValue(0, [0, 1, 3, 4, 6, 7, 9, 10], "Diminished (Half-Whole)", ("octatonic_hw",))
    diminished_wh = ScaleValue(0, [0, 2, 3, 5, 6, 8, 9, 11], "Diminished (Whole-Half)", ("octatonic_wh",))

    # Other common
    phrygian_dominant = ScaleValue(0, [0, 1, 4, 5, 7, 8, 10], "Phrygian Dominant", ("spanish_phrygian",))
    hungarian_minor = ScaleValue(0, [0, 2, 3, 6, 7, 8, 11], "Hungarian Minor")
    double_harmonic = ScaleValue(0, [0, 1, 4, 5, 7, 8, 11], "Double Harmonic", ("gypsy",))
    bebop_dominant = ScaleValue(0, [0, 2, 4, 5, 7, 9, 10, 11], "Bebop Dominant")
    bebop_major = ScaleValue(0, [0, 2, 4, 5, 7, 8, 9, 11], "Bebop Major")

    @property
    def offset(self) -> int:
        """Get offset from key tonic."""
        return self.value.offset

    @property
    def intervals(self) -> List[int]:
        """Get scale intervals."""
        return self.value.intervals

    @property
    def display_name(self) -> str:
        """Get display name."""
        return self.value.display_name

    @property
    def alternative_names(self) -> Tuple[str, ...]:
        """Get alternative names."""
        return self.value.alternative_names

    @property
    def num_notes(self) -> int:
        """Number of notes in scale."""
        return self.value.num_notes

    @property
    def steps(self) -> List[int]:
        """Get step sizes between notes."""
        return self.value.steps

    @classmethod
    def get(cls, name: str) -> Optional['Scale']:
        """Get scale by name (case-insensitive, supports alternatives)."""
        normalized = name.lower().replace(' ', '_').replace('-', '_')

        # Try direct lookup
        try:
            return cls[normalized]
        except KeyError:
            pass

        # Try alternative names
        for scale in cls:
            if scale.display_name.lower() == normalized:
                return scale
            for alt in scale.alternative_names:
                if alt.lower() == normalized:
                    return scale

        return None

    @classmethod
    def from_intervals(cls, intervals: List[int]) -> Optional['Scale']:
        """Find scale by exact interval pattern."""
        # Normalize intervals to start with 0 and end with <12
        if not intervals or intervals[0] != 0:
            return None

        for scale in cls:
            if scale.intervals == intervals:
                return scale
        return None

    def get_notes(self, root_pc: int) -> List[int]:
        """Get pitch classes for this scale given a root."""
        return [(root_pc + interval) % 12 for interval in self.intervals]

    def get_chord_qualities(self) -> Dict[int, str]:
        """Get chord qualities for each scale degree."""
        qualities = {}
        for i, interval in enumerate(self.intervals):
            # Determine triad quality
            third = (interval + 2) % 12
            fifth = (interval + 4) % 12

            has_major_third = third in self.intervals
            has_minor_third = (third - 1) % 12 in self.intervals
            has_perfect_fifth = fifth in self.intervals
            has_diminished_fifth = (fifth - 1) % 12 in self.intervals

            if has_major_third and has_perfect_fifth:
                qualities[i] = "major"
            elif has_minor_third and has_perfect_fifth:
                qualities[i] = "minor"
            elif has_minor_third and has_diminished_fifth:
                qualities[i] = "diminished"
            elif has_major_third and has_diminished_fifth:
                qualities[i] = "augmented"
            else:
                qualities[i] = "unknown"

        return qualities


# ═══════════════════════════════════════════════════════════════
# Scale Configuration
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class ScaleConfig:
    """Global scale configuration."""
    default_scale: Scale = Scale.major
    default_key: Key = Key.C  # Now Key is imported


# Singleton instance
SCALE_CONFIG = ScaleConfig()


# ═══════════════════════════════════════════════════════════════
# Convenience Lookup Functions (Backward Compatible)
# ═══════════════════════════════════════════════════════════════

def get_scale_data(name: str) -> Optional[Tuple[int, List[int]]]:
    """Get scale data as tuple (offset, intervals)."""
    scale = Scale.get(name)
    if scale:
        return (scale.offset, scale.intervals)
    return None


def get_scale_intervals(name: str) -> List[int]:
    """Get scale intervals by name."""
    scale = Scale.get(name)
    return scale.intervals if scale else [0, 2, 4, 5, 7, 9, 11]


def get_scale_offset(name: str) -> int:
    """Get scale offset by name."""
    scale = Scale.get(name)
    return scale.offset if scale else 0


# ═══════════════════════════════════════════════════════════════
# Backward Compatible Dictionary
# ═══════════════════════════════════════════════════════════════

# Recreate original SCALE_INTERVALS dictionary format
SCALE_INTERVALS: Dict[str, Tuple[int, List[int]]] = {
    scale.display_name.lower().replace(' ', '_'): (scale.offset, scale.intervals)
    for scale in Scale
}

# Add alternative names for backward compatibility
SCALE_INTERVALS.update({
    "ionian": (0, [0, 2, 4, 5, 7, 9, 11]),
    "aeolian": (-3, [0, 2, 3, 5, 7, 9, 10]),
})

# ═══════════════════════════════════════════════════════════════
# Main / Example Usage
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("═" * 60)
    print("Musical Scales Examples")
    print("═" * 60)

    # Basic scale lookup
    print("\n🎵 Scale Lookup:")
    for name in ["major", "minor", "dorian", "blues_minor", "whole_tone"]:
        scale = Scale.get(name)
        if scale:
            intervals_str = " ".join(f"{i:2}" for i in scale.intervals)
            print(f"  {scale.display_name:20} [{intervals_str}]")

    # Scale properties
    print("\n📊 Scale Properties:")
    major = Scale.get("major")
    if major:
        print(f"  Major scale has {major.num_notes} notes")
        print(f"  Step pattern: {major.steps}")

    # Get notes in a scale
    print("\n🎼 C Major Scale:")
    cmajor = Scale.get("major")
    if cmajor:
        notes = cmajor.get_notes(0)  # C=0
        note_names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        note_strs = [note_names[n] for n in notes]
        print(f"  {' '.join(note_strs)}")

    # A minor scale
    print("\n🎼 A Minor Scale:")
    aminor = Scale.get("minor")
    if aminor:
        notes = aminor.get_notes(9)  # A=9
        note_names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        note_strs = [note_names[n] for n in notes]
        print(f"  {' '.join(note_strs)}")

    # Blues scale
    print("\n🎸 C Blues Scale:")
    blues = Scale.get("blues_minor")
    if blues:
        notes = blues.get_notes(0)
        note_names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        note_strs = [note_names[n] for n in notes]
        print(f"  {' '.join(note_strs)}")

    # Find scale by intervals
    print("\n🔍 Find by Intervals:")
    test_intervals = [0, 2, 4, 5, 7, 9, 11]
    scale = Scale.from_intervals(test_intervals)
    if scale:
        print(f"  Intervals {test_intervals} -> {scale.display_name}")

    # Scale steps
    print("\n📝 Scale Step Patterns:")
    for scale_name in ["major", "harmonic_minor", "whole_tone", "diminished_hw"]:
        scale = Scale.get(scale_name)
        if scale:
            steps_str = " ".join(str(s) for s in scale.steps)
            print(f"  {scale.display_name:20} {steps_str}")

    # Configuration
    print("\n⚙️ Configuration:")
    print(f"  Default scale: {SCALE_CONFIG.default_scale.display_name}")
    print(f"  Default key: {SCALE_CONFIG.default_key.display_name}")

    # Backward compatibility
    print("\n📦 Backward Compatible:")
    scale_data = get_scale_data("dorian")
    if scale_data:
        offset, intervals = scale_data
        print(f"  get_scale_data('dorian') -> ({offset}, {intervals[:3]}...{intervals[-3:]})")

    print(f"  Original SCALE_INTERVALS has {len(SCALE_INTERVALS)} entries")

    print("\n✅ Done!")