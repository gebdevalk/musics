from dataclasses import dataclass
from fractions import Fraction
from enum import Enum
from typing import Optional


# ═══════════════════════════════════════════════════════════════
# Note Length Value
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class NoteLengthValue:
    """Note length value as Fraction of a whole note."""
    length: Fraction
    name: str
    is_dotted: bool = False
    is_triplet: bool = False

    def __post_init__(self):
        """Validate note length properties."""
        if self.is_dotted and self.is_triplet:
            raise ValueError("Note cannot be both dotted and triplet")
        if self.length <= 0:
            raise ValueError("Length must be positive")

    @property
    def as_float(self) -> float:
        """Return as float (may lose precision)."""
        return float(self.length)

    def __float__(self) -> float:
        return self.as_float

    def __repr__(self) -> str:
        return f"NoteLengthValue({self.length}, '{self.name}')"


# ═══════════════════════════════════════════════════════════════
# Note Length Enum
# ═══════════════════════════════════════════════════════════════

class NoteLength(Enum):
    """Standard note lengths as Fractions of a whole note."""

    # Standard notes
    whole = NoteLengthValue(Fraction(1, 1), "whole", is_dotted=False, is_triplet=False)
    half = NoteLengthValue(Fraction(1, 2), "half", is_dotted=False, is_triplet=False)
    quarter = NoteLengthValue(Fraction(1, 4), "quarter", is_dotted=False, is_triplet=False)
    eighth = NoteLengthValue(Fraction(1, 8), "eighth", is_dotted=False, is_triplet=False)
    sixteenth = NoteLengthValue(Fraction(1, 16), "sixteenth", is_dotted=False, is_triplet=False)
    thirtysecond = NoteLengthValue(Fraction(1, 32), "thirtysecond", is_dotted=False, is_triplet=False)

    # Dotted notes (original * 3/2)
    dotted_whole = NoteLengthValue(Fraction(3, 2), "dotted whole", is_dotted=True, is_triplet=False)
    dotted_half = NoteLengthValue(Fraction(3, 4), "dotted half", is_dotted=True, is_triplet=False)
    dotted_quarter = NoteLengthValue(Fraction(3, 8), "dotted quarter", is_dotted=True, is_triplet=False)
    dotted_eighth = NoteLengthValue(Fraction(3, 16), "dotted eighth", is_dotted=True, is_triplet=False)

    # Triplets (original * 2/3)
    half_triplet = NoteLengthValue(Fraction(1, 3), "half triplet", is_dotted=False, is_triplet=True)
    quarter_triplet = NoteLengthValue(Fraction(1, 6), "quarter triplet", is_dotted=False, is_triplet=True)
    eighth_triplet = NoteLengthValue(Fraction(1, 12), "eighth triplet", is_dotted=False, is_triplet=True)

    @property
    def length(self) -> Fraction:
        """Get length as Fraction of a whole note."""
        return self.value.length

    @property
    def display_name(self) -> str:
        """Get display name."""
        return self.value.name

    @property
    def is_dotted(self) -> bool:
        return self.value.is_dotted

    @property
    def is_triplet(self) -> bool:
        return self.value.is_triplet

    @property
    def as_float(self) -> float:
        """Get length as float (may lose precision)."""
        return self.value.as_float

    @classmethod
    def get(cls, name: str) -> Optional['NoteLength']:
        """Get note length by name (case-insensitive)."""
        # Normalize: lowercase, replace spaces with underscores
        normalized = name.lower().replace(' ', '_').replace('-', '_')

        # Try direct lookup
        for member in cls:
            if member.name == normalized:
                return member
            if member.display_name.lower() == name.lower():
                return member

        return None

    @classmethod
    def from_fraction(cls, length: Fraction) -> Optional['NoteLength']:
        """Get note length exactly matching given Fraction."""
        for note in cls:
            if note.length == length:
                return note
        return None

    def dotted(self) -> Optional['NoteLength']:
        """Get dotted version of this note length."""
        if self.is_dotted or self.is_triplet:
            return None

        # Map standard notes to dotted versions
        dotted_map = {
            NoteLength.whole: NoteLength.dotted_whole,
            NoteLength.half: NoteLength.dotted_half,
            NoteLength.quarter: NoteLength.dotted_quarter,
            NoteLength.eighth: NoteLength.dotted_eighth,
        }
        return dotted_map.get(self)

    def triplet(self) -> Optional['NoteLength']:
        """Get triplet version of this note length."""
        if self.is_dotted or self.is_triplet:
            return None

        # Map standard notes to triplet versions
        triplet_map = {
            NoteLength.half: NoteLength.half_triplet,
            NoteLength.quarter: NoteLength.quarter_triplet,
            NoteLength.eighth: NoteLength.eighth_triplet,
        }
        return triplet_map.get(self)


# ═══════════════════════════════════════════════════════════════
# Note Length Configuration
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class NoteLengthConfig:
    """Global note length configuration."""
    default_note: NoteLength = NoteLength.quarter
    min_length: Fraction = Fraction(1, 32)  # Thirty-second note
    max_length: Fraction = Fraction(3, 2)  # Dotted whole note

    @property
    def min_note(self) -> NoteLength:
        """Shortest standard note."""
        return NoteLength.thirtysecond

    @property
    def max_note(self) -> NoteLength:
        """Longest standard note."""
        return NoteLength.dotted_whole

    def length_to_bars(self, length: Fraction, beats_per_bar: int = 4) -> float:
        """Convert note length (as fraction of whole note) to number of bars."""
        # Whole note = 1, so length / (beats_per_bar / 4) = bars
        return float(length / (Fraction(beats_per_bar, 4)))

    def bars_to_length(self, bars: float, beats_per_bar: int = 4) -> Fraction:
        """Convert bars to note length (as fraction of whole note)."""
        return Fraction(bars) * Fraction(beats_per_bar, 4)

    def clamp(self, length: Fraction) -> Fraction:
        """Clamp note length to valid range."""
        return max(self.min_length, min(self.max_length, length))

    def normalize(self, length: Fraction) -> float:
        """Normalize length to 0.0-1.0 within range."""
        if self.min_length == self.max_length:
            return 0.0
        return float((length - self.min_length) / (self.max_length - self.min_length))

    def denormalize(self, normalized: float) -> Fraction:
        """Convert normalized 0.0-1.0 to length within range."""
        return self.min_length + Fraction(normalized) * (self.max_length - self.min_length)


# Singleton instance
NOTE_CONFIG = NoteLengthConfig()


# ═══════════════════════════════════════════════════════════════
# Convenience lookup functions (backward compatible)
# ═══════════════════════════════════════════════════════════════

def get_note_length(name: str) -> Optional[NoteLength]:
    """Get note length by name."""
    return NoteLength.get(name)


def get_note_length_value(name: str) -> Fraction:
    """Get length as Fraction for note length by name."""
    note = NoteLength.get(name)
    return note.length if note else Fraction(1, 4)


def get_note_length_float(name: str) -> float:
    """Get length as float for note length by name."""
    note = NoteLength.get(name)
    return note.as_float if note else 0.25


def get_note_by_length(length: Fraction) -> Optional[NoteLength]:
    """Get note length by exact Fraction value."""
    return NoteLength.from_fraction(length)


def get_note_by_float(length: float, tolerance: float = 0.001) -> Optional[NoteLength]:
    """Get note length by approximate float value."""
    for note in NoteLength:
        if abs(note.as_float - length) <= tolerance:
            return note
    return None


# ═══════════════════════════════════════════════════════════════
# Main / Example Usage
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("═" * 60)
    print("Note Length Examples")
    print("═" * 60)

    # Basic note lengths
    print("\n📌 Basic Note Lengths:")
    for name in ["whole", "half", "quarter", "eighth", "sixteenth", "thirtysecond"]:
        note = NoteLength.get(name)
        if note:
            print(f"  {note.display_name:12} = {note.length} (≈ {note.as_float:.4f} of whole note)")

    # Dotted notes
    print("\n🔘 Dotted Notes:")
    for name in ["dotted_whole", "dotted_half", "dotted_quarter", "dotted_eighth"]:
        note = NoteLength.get(name)
        if note:
            print(f"  {note.display_name:15} = {note.length} (≈ {note.as_float:.4f})")

    # Triplets
    print("\n🌀 Triplets:")
    for name in ["half_triplet", "quarter_triplet", "eighth_triplet"]:
        note = NoteLength.get(name)
        if note:
            print(f"  {note.display_name:15} = {note.length} (≈ {note.as_float:.4f})")

    # Conversions
    print("\n🔄 Conversions:")
    quarter = NoteLength.quarter
    dotted = quarter.dotted()
    triplet = quarter.triplet()
    if dotted and triplet:
        print(
            f"  Quarter + Dotted quarter = {quarter.length + dotted.length} (≈ {float(quarter.length + dotted.length):.4f})")
        print(f"  3 × Eighth triplet = {3 * NoteLength.eighth_triplet.length}")

    # Configuration
    print("\n⚙️ Configuration:")
    print(f"  Min length: {NOTE_CONFIG.min_length} (≈ {float(NOTE_CONFIG.min_length):.4f})")
    print(f"  Max length: {NOTE_CONFIG.max_length} (≈ {float(NOTE_CONFIG.max_length):.4f})")
    print(f"  Default: {NOTE_CONFIG.default_note.display_name}")

    # Lookup functions
    print("\n🔍 Lookup Examples:")
    note = get_note_length("dotted_quarter")
    if note:
        print(f"  get_note_length('dotted_quarter') -> {note.display_name}")
    print(f"  get_note_length_value('half') -> {get_note_length_value('half')}")

    note = get_note_by_length(Fraction(1, 6))
    if note:
        print(f"  get_note_by_length(Fraction(1, 6)) -> {note.display_name}")

    # Bars conversion
    print("\n📊 Bars Conversion (4/4 time):")
    whole = NoteLength.whole
    bars = NOTE_CONFIG.length_to_bars(whole.length, beats_per_bar=4)
    print(f"  Whole note = {bars} bar(s)")
    print(f"  2 bars = {NOTE_CONFIG.bars_to_length(2, beats_per_bar=4)} of whole note")

    print("\n✅ Done!")