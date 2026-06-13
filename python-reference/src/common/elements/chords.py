from enum import Enum
from typing import List, Tuple, Optional
from dataclasses import dataclass


@dataclass(frozen=True)
class Chord:
    """A chord pattern with intervals and quality."""
    name: str
    symbol: str  # Chord symbol like 'm', 'm7', 'M7', '7', etc.
    intervals: List[int]  # Semitone intervals from root
    aliases: List[str] = None  # Alternative symbols (e.g., '-' for minor)

    def __post_init__(self):
        if self.aliases is None:
            object.__setattr__(self, 'aliases', [])


class Chords:
    """Predefined chord patterns."""

    # Triads
    major = Chord("major", "", [0, 4, 7], ["M", "maj", "Δ"])
    minor = Chord("minor", "m", [0, 3, 7], ["min", "-"])
    augmented = Chord("augmented", "aug", [0, 4, 8], ["+"])
    diminished = Chord("diminished", "dim", [0, 3, 6], ["°"])

    # Seventh chords
    dominant_7 = Chord("dominant 7th", "7", [0, 4, 7, 10], ["dom7"])
    major_7 = Chord("major 7th", "M7", [0, 4, 7, 11], ["maj7", "Δ7"])
    minor_7 = Chord("minor 7th", "m7", [0, 3, 7, 10], ["min7", "-7"])
    half_diminished = Chord("half diminished", "m7b5", [0, 3, 6, 10], ["ø", "m7-5"])
    diminished_7 = Chord("diminished 7th", "dim7", [0, 3, 6, 9], ["°7"])
    augmented_7 = Chord("augmented 7th", "aug7", [0, 4, 8, 10], ["+7", "7#5"])
    minor_major_7 = Chord("minor major 7th", "mM7", [0, 3, 7, 11], ["mΔ7", "-Δ7"])

    # Sixth chords
    major_6 = Chord("major 6th", "6", [0, 4, 7, 9], ["M6"])
    minor_6 = Chord("minor 6th", "m6", [0, 3, 7, 9], ["min6"])

    # Ninth chords
    dominant_9 = Chord("dominant 9th", "9", [0, 4, 7, 10, 14], ["dom9"])
    major_9 = Chord("major 9th", "M9", [0, 4, 7, 11, 14], ["maj9", "Δ9"])
    minor_9 = Chord("minor 9th", "m9", [0, 3, 7, 10, 14], ["min9", "-9"])

    # Eleventh and thirteenth
    dominant_11 = Chord("dominant 11th", "11", [0, 4, 7, 10, 14, 17])
    dominant_13 = Chord("dominant 13th", "13", [0, 4, 7, 10, 14, 17, 21])

    # Suspended chords
    sus2 = Chord("suspended 2nd", "sus2", [0, 2, 7])
    sus4 = Chord("suspended 4th", "sus4", [0, 5, 7])
    sus4_7 = Chord("suspended 4th 7th", "7sus4", [0, 5, 7, 10], ["sus7"])

    # Extended altered chords
    dominant_7b5 = Chord("dominant 7th flat 5", "7b5", [0, 4, 6, 10], ["7-5"])
    dominant_7b9 = Chord("dominant 7th flat 9", "7b9", [0, 4, 7, 10, 13])
    dominant_7  # 9 = Chord("dominant 7th sharp 9", "7#9", [0, 4, 7, 10, 15])
    dominant_7  # 11 = Chord("dominant 7th sharp 11", "7#11", [0, 4, 7, 10, 14, 18])
    dominant_7b13 = Chord("dominant 7th flat 13", "7b13", [0, 4, 7, 10, 14, 20])

    # Added tone chords
    add9 = Chord("add 9th", "add9", [0, 4, 7, 14])
    madd9 = Chord("minor add 9th", "madd9", [0, 3, 7, 14])
    add11 = Chord("add 11th", "add11", [0, 4, 7, 17])

    # Power chord
    power = Chord("power", "5", [0, 7])


@dataclass(frozen=True)
class KeyChord:
    """A chord in a specific key/root."""
    key: 'Key'
    chord: Chord
    inversion: int = 0  # 0=root, 1=first inversion, etc.

    @property
    def pitches(self) -> List[int]:
        """Calculate chord pitches as semitone values."""
        root = self.key.tonic
        pitches = [(root + interval) % 12 for interval in self.chord.intervals]

        # Apply inversion
        if self.inversion > 0:
            pitches = pitches[self.inversion:] + pitches[:self.inversion]

        return pitches

    @property
    def pitch_names(self) -> List[str]:
        """Get the names of the pitches using appropriate key signature."""
        naming_key = self._get_naming_key()
        return [naming_key.get_pitch_name(p) for p in self.pitches]

    def _get_naming_key(self) -> 'Key':
        """Get the appropriate key for naming chord tones."""
        from common.elements.key_scale import Key as KeyClass

        root_tonic = self.key.tonic
        chord_symbol = self.chord.symbol

        # For minor chords, use relative major (up a minor 3rd = 3 semitones)
        if chord_symbol.startswith('m') and chord_symbol not in ['M', 'M7', 'maj', 'maj7']:
            relative_major_tonic = (root_tonic + 3) % 12
            for key in KeyClass:
                if key.tonic == relative_major_tonic and key.accidentals <= 0:
                    return key
            for key in KeyClass:
                if key.tonic == relative_major_tonic:
                    return key

        # For dominant 7th chords, use the key a 5th BELOW (adds one flat)
        # C7 → F major (1 flat: Bb) gives Bb as the 7th
        elif chord_symbol in ['7', '9', '11', '13', '7b9', '7#9', '7b5', '7#11', '7b13']:
            # Down a 5th is -7 semitones (or +5 semitones mod 12)
            dominant_key_tonic = (root_tonic - 7) % 12  # -7 semitones = down a 5th
            for key in KeyClass:
                if key.tonic == dominant_key_tonic and key.accidentals <= 0:
                    return key
            for key in KeyClass:
                if key.tonic == dominant_key_tonic:
                    return key

        # For half-diminished, use relative major
        elif chord_symbol in ['m7b5', 'ø']:
            relative_major_tonic = (root_tonic + 3) % 12
            for key in KeyClass:
                if key.tonic == relative_major_tonic:
                    return key

        # For augmented, use parallel major
        elif chord_symbol in ['aug', '+', 'aug7', '+7']:
            for key in KeyClass:
                if key.tonic == root_tonic and key.accidentals >= 0:
                    return key

        # Default: use parallel major
        else:
            for key in KeyClass:
                if key.tonic == root_tonic and key.accidentals >= 0:
                    return key
                elif key.tonic == root_tonic:
                    return key

        return self.key

    @property
    def symbol(self) -> str:
        """Get the chord symbol (e.g., 'Cm7', 'F#M7')."""
        key_name = self.key.pretty_name if hasattr(self.key, 'pretty_name') else self.key.name
        if self.chord.symbol:
            return f"{key_name}{self.chord.symbol}"
        return key_name

    def __str__(self) -> str:
        return self.symbol


class ChordParserMeta(type):
    """Metaclass for ChordParser that builds the chord map."""

    def __init__(cls, name, bases, namespace):
        super().__init__(name, bases, namespace)
        if hasattr(cls, '_build_chord_map'):
            cls._build_chord_map()


class ChordParser(metaclass=ChordParserMeta):
    """Parse chord symbols like 'Cm', 'CM7', 'F#m7b5', 'Bb7#9', etc."""

    _chord_map = {}

    @classmethod
    def _build_chord_map(cls):
        """Build a mapping from chord symbols to Chord objects."""
        for attr_name in dir(Chords):
            if not attr_name.startswith('_'):
                chord = getattr(Chords, attr_name)
                if isinstance(chord, Chord):
                    # Map the main symbol
                    cls._chord_map[chord.symbol] = chord
                    # Map aliases
                    for alias in chord.aliases:
                        cls._chord_map[alias] = chord

    @classmethod
    def parse(cls, chord_symbol: str) -> Tuple[Optional[str], Optional[Chord]]:
        """Parse a chord symbol into (root_name, chord)."""
        chord_symbol = chord_symbol.strip()

        # Define root patterns (longest first)
        root_patterns = [
            'C#', 'F#', 'G#', 'D#', 'A#', 'E#', 'B#',  # Sharps
            'Db', 'Eb', 'Gb', 'Ab', 'Bb',  # Flats
            'C', 'D', 'E', 'F', 'G', 'A', 'B'  # Naturals
        ]

        # Find the root
        root = None
        remaining = chord_symbol

        for pattern in root_patterns:
            if chord_symbol.startswith(pattern):
                root = pattern
                remaining = chord_symbol[len(pattern):]
                break

        if root is None:
            raise ValueError(f"Could not parse root from: {chord_symbol}")

        # Handle special case: 'M' for major (CM7, CM9, etc.)
        if remaining and remaining[0] == 'M' and len(remaining) > 1 and remaining[1].isdigit():
            remaining = 'maj' + remaining[1:]

        # Handle empty remaining (major triad)
        if remaining == '':
            remaining = ''  # Empty string maps to major triad

        # Find the chord
        chord = cls._chord_map.get(remaining)

        # Try to find by prefix if exact match fails
        if chord is None and remaining:
            matches = [sym for sym in cls._chord_map.keys() if remaining.startswith(sym)]
            if matches:
                longest = max(matches, key=len)
                chord = cls._chord_map[longest]
                if remaining != longest:
                    print(f"Warning: '{remaining}' partially matched as '{longest}'")

        return root, chord

    @classmethod
    def create(cls, chord_symbol: str, key: 'Key' = None) -> KeyChord:
        """Create a KeyChord from a chord symbol.

        If key is provided, use it as the root key.
        Otherwise, derive the key from the chord root.
        """
        root_name, chord = cls.parse(chord_symbol)
        if chord is None:
            raise ValueError(f"Unknown chord symbol: {chord_symbol}")

        if key is None:
            # Create key from root name
            from common.elements.key_scale import Key as KeyClass
            if hasattr(KeyClass, 'from_string'):
                key = KeyClass.from_string(root_name)
            else:
                raise ValueError(f"Unknown root: {root_name}")

        return KeyChord(key, chord)


# Usage examples
if __name__ == '__main__':
    # Import Key here for testing
    from common.elements.key_scale import Key

    # Add this to your __main__ section:

    print("\n" + "=" * 60)
    print("=== ALL CHORDS ON C ===")
    print("=" * 60)

    c_chords = [
        "C", "Cm", "Caug", "Cdim",
        "C7", "CM7", "Cm7", "Cm7b5", "Cdim7", "Caug7", "CmM7",
        "C6", "Cm6",
        "C9", "CM9", "Cm9",
        "C11", "C13",
        "Csus2", "Csus4", "C7sus4",
        "C7b5", "C7b9", "C7#9", "C7#11", "C7b13",
        "Cadd9", "Cmadd9", "Cadd11",
        "C5"
    ]

    for chord_symbol in c_chords:
        try:
            chord = ChordParser.create(chord_symbol)
            print(f"{chord_symbol:10} → {chord.pitch_names}")
        except Exception as e:
            print(f"{chord_symbol:10} → ERROR: {e}")

    print("\n" + "=" * 60)
    print("=== ALL CHORDS ON Es (Eb) ===")
    print("=" * 60)

    eb_chords = [
        "Eb", "Ebm", "Ebaug", "Ebdim",
        "Eb7", "EbM7", "Ebm7", "Ebm7b5", "Ebdim7", "Ebaug7", "EbmM7",
        "Eb6", "Ebm6",
        "Eb9", "EbM9", "Ebm9",
        "Eb11", "Eb13",
        "Ebsus2", "Ebsus4", "Eb7sus4",
        "Eb7b5", "Eb7b9", "Eb7#9", "Eb7#11", "Eb7b13",
        "Ebadd9", "Ebmadd9", "Ebadd11",
        "Eb5"
    ]

    for chord_symbol in eb_chords:
        try:
            chord = ChordParser.create(chord_symbol)
            print(f"{chord_symbol:10} → {chord.pitch_names}")
        except Exception as e:
            print(f"{chord_symbol:10} → ERROR: {e}")

    print("\n" + "=" * 60)
    print("=== ALL CHORDS ON A ===")
    print("=" * 60)

    a_chords = [
        "A", "Am", "Aaug", "Adim",
        "A7", "AM7", "Am7", "Am7b5", "Adim7", "Aaug7", "AmM7",
        "A6", "Am6",
        "A9", "AM9", "Am9",
        "A11", "A13",
        "Asus2", "Asus4", "A7sus4",
        "A7b5", "A7b9", "A7#9", "A7#11", "A7b13",
        "Aadd9", "Amadd9", "Aadd11",
        "A5"
    ]

    for chord_symbol in a_chords:
        try:
            chord = ChordParser.create(chord_symbol)
            print(f"{chord_symbol:10} → {chord.pitch_names}")
        except Exception as e:
            print(f"{chord_symbol:10} → ERROR: {e}")

    print("\n" + "=" * 60)
    print("=== INVERSION TESTS ON DIFFERENT CHORDS ===")
    print("=" * 60)

    # Test inversions on different chord types
    test_chords = [
        ("C", "Major triad"),
        ("Cm", "Minor triad"),
        ("C7", "Dominant 7th"),
        ("CM7", "Major 7th"),
        ("Cm7", "Minor 7th"),
        ("Caug", "Augmented"),
        ("Cdim", "Diminished"),
    ]

    for chord_symbol, chord_type in test_chords:
        chord = ChordParser.create(chord_symbol)
        print(f"\n{chord_symbol} ({chord_type}):")
        print(f"  Root position:     {chord.pitch_names}")

        # Try inversions (if chord has enough notes)
        for inv in range(1, min(3, len(chord.chord.intervals))):
            inverted = KeyChord(chord.key, chord.chord, inversion=inv)
            print(f"  {inv}st inversion:    {inverted.pitch_names}" if inv == 1
                  else f"  {inv}nd inversion:    {inverted.pitch_names}")

    print("\n" + "=" * 60)
    print("=== SPOKEN TESTS (C7 should be C, E, G, B-flat) ===")
    print("=" * 60)

    c7 = ChordParser.create("C7")
    print(f"C7: {c7.pitch_names}")
    print(f"Expected: ['C', 'E', 'G', 'Bb'] → {'✓ PASS' if c7.pitch_names == ['C', 'E', 'G', 'Bb'] else '✗ FAIL'}")

    c7b9 = ChordParser.create("C7b9")
    print(f"C7b9: {c7b9.pitch_names}")
    print(
        f"Expected: ['C', 'E', 'G', 'Bb', 'Db'] → {'✓ PASS' if c7b9.pitch_names == ['C', 'E', 'G', 'Bb', 'Db'] else '✗ FAIL'}")

    eb_minor = ChordParser.create("Ebm")
    print(f"Ebm: {eb_minor.pitch_names}")
    print(f"Expected: ['Eb', 'Gb', 'Bb'] → {'✓ PASS' if eb_minor.pitch_names == ['Eb', 'Gb', 'Bb'] else '✗ FAIL'}")

    a_major7 = ChordParser.create("AM7")
    print(f"AM7: {a_major7.pitch_names}")
    print(
        f"Expected: ['A', 'C#', 'E', 'G#'] → {'✓ PASS' if a_major7.pitch_names == ['A', 'C#', 'E', 'G#'] else '✗ FAIL'}")

    fsharp_minor = ChordParser.create("F#m")
    print(f"F#m: {fsharp_minor.pitch_names}")
    print(f"Expected: ['F#', 'A', 'C#'] → {'✓ PASS' if fsharp_minor.pitch_names == ['F#', 'A', 'C#'] else '✗ FAIL'}")