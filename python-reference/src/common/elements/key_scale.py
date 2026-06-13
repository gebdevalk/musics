from enum import Enum, EnumMeta
from typing import List
from dataclasses import dataclass

# Pitch naming helpers
SHARP_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
FLAT_NAMES = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]

# # Helper lists for accidentals in order of appearance in key signatures
# SHARP_ACCIDENTALS = ["fis", "cis", "gis", "dis", "ais", "eis", "bis"]
# FLAT_ACCIDENTALS = ["ces", "ges", "des", "as", "es", "bes"]


@dataclass(frozen=True, slots=True)
class Scale:
    """Scale pattern with intervals and offset."""
    name: str
    intervals: List[int]  # semitone steps between degrees
    offset: int  # transposition offset from tonic

@dataclass(frozen=True, slots=True)
class Scales:
    # Major scale modes
    ionian = Scale("ionian", [2, 2, 1, 2, 2, 2, 1], 0)
    dorian = Scale("dorian", [2, 1, 2, 2, 2, 1, 2], 2)
    phrygian = Scale("phrygian", [1, 2, 2, 2, 1, 2, 2], 4)
    lydian = Scale("lydian", [2, 2, 2, 1, 2, 2, 1], 5)
    mixolydian = Scale("mixolydian", [2, 2, 1, 2, 2, 1, 2], 7)
    aeolian = Scale("aeolian", [2, 1, 2, 2, 1, 2, 2], -3)
    locrian = Scale("locrian", [1, 2, 2, 1, 2, 2, 2], -1)

    # Hypo modes
    hypoionian = Scale("hypoionian", [2, 2, 2, 1, 2, 2, 1], 7)
    hypodorian = Scale("hypodorian", [2, 2, 1, 2, 2, 1, 2], -3)
    hypophrygian = Scale("hypophrygian", [2, 1, 2, 2, 1, 2, 2], -1)
    hypolydian = Scale("hypolydian", [1, 2, 2, 1, 2, 2, 2], 0)
    hypomixolydian = Scale("hypomixolydian", [2, 1, 2, 2, 2, 1, 2], 2)
    hypoaeolian = Scale("hypoaeolian", [1, 2, 2, 2, 1, 2, 2], 4)
    hypolocrian = Scale("hypolocrian", [2, 1, 2, 2, 1, 2, 2], 5)

    # Pentatonic
    pentatonic_major = Scale("pentatonic_major", [2, 2, 3, 2, 3], 0)
    pentatonic_minor = Scale("pentatonic_minor", [3, 2, 2, 3, 2], 0)

    # Blues
    blues_major = Scale("blues_major", [2, 1, 1, 3, 2], 0)
    blues_minor = Scale("blues_minor", [3, 2, 1, 1, 3], 0)

    # Symmetric
    whole_tone = Scale("whole_tone", [2, 2, 2, 2, 2, 2], 0)
    diminished_hw = Scale("diminished_hw", [1, 2, 1, 2, 1, 2, 1, 2], 0)
    diminished_wh = Scale("diminished_wh", [2, 1, 2, 1, 2, 1, 2, 1], 0)

    # Other common
    phrygian_dominant = Scale("phrygian_dominant", [1, 3, 1, 2, 1, 2, 2], 0)
    hungarian_minor = Scale("hungarian_minor", [2, 1, 3, 1, 1, 3, 1], 0)
    double_harmonic = Scale("double_harmonic", [1, 3, 1, 2, 1, 3, 1], 0)
    bebop_dominant = Scale("bebop_dominant", [2, 2, 1, 2, 2, 1, 1, 1], 0)
    bebop_major = Scale("bebop_major", [2, 2, 1, 2, 1, 1, 2, 1], 0)

    # Minor scale variations
    natural_minor = Scale("natural_minor", [2, 1, 2, 2, 1, 2, 2], -3)
    harmonic_minor = Scale("harmonic_minor", [2, 1, 2, 2, 1, 3, 1], -3)
    melodic_minor = Scale("melodic_minor", [2, 1, 2, 2, 2, 2, 1], -3)

    # Basic scales
    major = Scale("major", [2, 2, 1, 2, 2, 2, 1], 0)
    minor = Scale("minor", [2, 1, 2, 2, 1, 2, 2], -3)
    chromatic = Scale("chromatic", [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1], 0)
    augmented = Scale("augmented", [3, 1, 3, 1, 3, 1], 0)


@dataclass(frozen=True, slots=True)
class KeyScale:
    key: 'Key'
    scale: Scale

    @property
    def pitches(self) -> List[int]:
        """Calculate ascending pitches without modulo wrapping."""
        start = (self.key.tonic + self.scale.offset) % 12
        pitches = [start]
        current = start

        for interval in self.scale.intervals:
            current = current + interval
            pitches.append(current)

        return pitches[:-1]

    def pitches_in_octave(self, octave: int) -> List[int]:
        """Return scale pitches as absolute semitone values in given octave.
        Args:
            octave: Octave number where C in that octave = octave * 12
        Returns:
            List of absolute pitch values (e.g., C4=48 if using C0=0)
        """
        return [pitch + (octave * 12) for pitch in self.pitches]

    @property
    def pitch_names(self) -> List[str]:
        """Get the names of the pitches in this scale."""
        return [self.key.get_pitch_name(p) for p in self.pitches]


    def __str__(self) -> str:
        """Return a readable representation like 'C major: C D E F G A B'."""
        # return f"{self.key} {self.scale.name}: {' '.join(self.pitch_names)}"
        return f"{self.key}.{self.scale.name}"

class Key(Enum):
# class Key(Enum):
    """Enum representing musical keys with their accidentals and tonic."""

    def __init__(self, acc: int, tonic: int):
        self.accidentals: int = acc  # negative for flats, 0 for C, positive for sharps
        self.tonic: int = tonic  # distance from C in semitones (0=C, 1=C#, etc.)

    @classmethod
    def from_string(cls, key_str: str) -> 'Key':
        """Parse a key from string like 'Es', 'Eb', 'e flat', or 'F#'."""
        # Handle enum member names directly
        upper_str = key_str.upper()
        if hasattr(cls, upper_str):
            return getattr(cls, upper_str)

        # Handle pretty names
        pretty_mapping = {
            'GB': cls.GES, 'DB': cls.DES, 'AB': cls.AS, 'EB': cls.ES, 'BB': cls.BES,
            'F': cls.F, 'C': cls.C, 'G': cls.G, 'D': cls.D, 'A': cls.A,
            'E': cls.E, 'B': cls.B, 'F#': cls.FIS
        }

        normalized = key_str.upper().replace(' ', '')
        if normalized in pretty_mapping:
            return pretty_mapping[normalized]

        # Handle formats like "E FLAT"
        if 'FLAT' in normalized:
            letter = normalized[0]
            return pretty_mapping.get(f"{letter}B", cls.C)  # Default to C if not found
        elif 'SHARP' in normalized:
            letter = normalized[0]
            return pretty_mapping.get(f"{letter}#", cls.C)

        raise KeyError(f"Unknown key name: {key_str}")


    @classmethod
    def parse(cls, expression: str) -> 'KeyScale':
        """Parse a string like 'Es.major' or 'Eb.dorian' into a KeyScale."""
        if '.' not in expression:
            raise ValueError("Format should be 'Key.scale' like 'Es.major'")

        key_part, scale_part = expression.split('.')
        key = cls.from_string(key_part)
        return getattr(key, scale_part)

    def get_pitch_name(self, semitone: int) -> str:
        """Convert a semitone value to a pitch name based on the key's accidentals."""
        if self.accidentals >= 0:
            return SHARP_NAMES[semitone % 12]
        else:
            return FLAT_NAMES[semitone % 12]

    # Define the enum members with their (accidentals, tonic)
    # Flats
    GES = (-6, 6)  # Gb major
    DES = (-5, 1)  # Db major
    AS = (-4, 8)   # Ab major
    ES = (-3, 3)   # Eb major
    BES = (-2, 10)  # Bb major
    F = (-1, 5)    # F major

    # Natural
    C = (0, 0)     # C major

    # Sharps
    G = (1, 7)     # G major
    D = (2, 2)     # D major
    A = (3, 9)     # A major
    E = (4, 4)     # E major
    B = (5, 11)    # B major
    FIS = (6, 6)   # F# major

    # Major modes
    @property
    def ionian(self) -> KeyScale:
        return KeyScale(self, Scales.ionian)

    @property
    def dorian(self) -> KeyScale:
        return KeyScale(self, Scales.dorian)

    @property
    def phrygian(self) -> KeyScale:
        return KeyScale(self, Scales.phrygian)

    @property
    def lydian(self) -> KeyScale:
        return KeyScale(self, Scales.lydian)

    @property
    def mixolydian(self) -> KeyScale:
        return KeyScale(self, Scales.mixolydian)

    @property
    def aeolian(self) -> KeyScale:
        return KeyScale(self, Scales.aeolian)

    @property
    def locrian(self) -> KeyScale:
        return KeyScale(self, Scales.locrian)

    # Hypo modes
    @property
    def hypoionian(self) -> KeyScale:
        return KeyScale(self, Scales.hypoionian)

    @property
    def hypodorian(self) -> KeyScale:
        return KeyScale(self, Scales.hypodorian)

    @property
    def hypophrygian(self) -> KeyScale:
        return KeyScale(self, Scales.hypophrygian)

    @property
    def hypolydian(self) -> KeyScale:
        return KeyScale(self, Scales.hypolydian)

    @property
    def hypomixolydian(self) -> KeyScale:
        return KeyScale(self, Scales.hypomixolydian)

    @property
    def hypoaeolian(self) -> KeyScale:
        return KeyScale(self, Scales.hypoaeolian)

    @property
    def hypolocrian(self) -> KeyScale:
        return KeyScale(self, Scales.hypolocrian)

    # Pentatonic
    @property
    def pentatonic_major(self) -> KeyScale:
        return KeyScale(self, Scales.pentatonic_major)

    @property
    def pentatonic_minor(self) -> KeyScale:
        return KeyScale(self, Scales.pentatonic_minor)

    # Blues
    @property
    def blues_major(self) -> KeyScale:
        return KeyScale(self, Scales.blues_major)

    @property
    def blues_minor(self) -> KeyScale:
        return KeyScale(self, Scales.blues_minor)

    # Symmetric
    @property
    def whole_tone(self) -> KeyScale:
        return KeyScale(self, Scales.whole_tone)

    @property
    def diminished_hw(self) -> KeyScale:
        return KeyScale(self, Scales.diminished_hw)

    @property
    def diminished_wh(self) -> KeyScale:
        return KeyScale(self, Scales.diminished_wh)

    # Other common
    @property
    def phrygian_dominant(self) -> KeyScale:
        return KeyScale(self, Scales.phrygian_dominant)

    @property
    def hungarian_minor(self) -> KeyScale:
        return KeyScale(self, Scales.hungarian_minor)

    @property
    def double_harmonic(self) -> KeyScale:
        return KeyScale(self, Scales.double_harmonic)

    @property
    def bebop_dominant(self) -> KeyScale:
        return KeyScale(self, Scales.bebop_dominant)

    @property
    def bebop_major(self) -> KeyScale:
        return KeyScale(self, Scales.bebop_major)

    # Minor variations
    @property
    def natural_minor(self) -> KeyScale:
        return KeyScale(self, Scales.natural_minor)

    @property
    def harmonic_minor(self) -> KeyScale:
        return KeyScale(self, Scales.harmonic_minor)

    @property
    def melodic_minor(self) -> KeyScale:
        return KeyScale(self, Scales.melodic_minor)

    # Basic aliases
    @property
    def major(self) -> KeyScale:
        return KeyScale(self, Scales.major)

    @property
    def minor(self) -> KeyScale:
        return KeyScale(self, Scales.minor)

    @property
    def chromatic(self) -> KeyScale:
        return KeyScale(self, Scales.chromatic)

    @property
    def augmented(self) -> KeyScale:
        return KeyScale(self, Scales.augmented)

#
# def parse_key_scale(expression: str) -> KeyScale:
#     """Parse a string like 'Es.major' or 'Eb.dorian' into a KeyScale."""
#     if '.' not in expression:
#         raise ValueError("Format should be 'Key.scale' like 'Es.major'")
#
#     key_part, scale_part = expression.split('.')
#     key = Key.from_string(key_part)
#     return getattr(key, scale_part)

if __name__ == '__main__':
    # Usage
    ks = Key.parse("Es.major")
    print(ks)  # Eb major
    print(ks.pitch_names)

    ks = Key.parse("D.dorian")
    print(ks)  # Eb major
    print(ks.pitch_names)  # Eb dorian

    ks = Key.parse("E.dorian")
    print(ks)  # Eb major
    print(ks.pitch_names)  # Eb dorian

    ks = Key.parse("F#.phrygian")
    print(ks)  # F# phrygian
    print(ks.pitch_names)  # F# phrygian

    # print("=== Dorian modes with pitch names ===")
    # for key in [Key.C, Key.G, Key.D, Key.A, Key.E, Key.B]:
    #     ks = key.dorian
    #     print(f"{key.name} dorian: {ks.pitch_names}")
    #
    # print("\n=== Major scales with pitch names ===")
    # for key in [Key.C, Key.G, Key.D, Key.A, Key.E, Key.B, Key.F, Key.BES, Key.ES]:
    #     ks = key.major
    #     print(f"{key.name} major: {ks.pitch_names}")
    #
    # print("\n=== Individual pitch naming comparison ===")
    # for semitone in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]:
    #     print(f"Semitone {semitone:2d}: C major={Key.C.get_pitch_name(semitone):>3}, "
    #           f"G major={Key.G.get_pitch_name(semitone):>3}, "
    #           f"F major={Key.F.get_pitch_name(semitone):>3}")
    #
    # print(Key["F#"].major)