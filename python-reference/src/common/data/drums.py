from dataclasses import dataclass
from enum import Enum
from typing import Optional, Tuple, Set, Dict, List


# ═══════════════════════════════════════════════════════════════
# Drum Note Value
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class DrumValue:
    """MIDI drum note information."""
    note_number: int
    display_name: str
    abbreviation: Optional[str] = None
    group: Optional[str] = None

    @property
    def is_percussion(self) -> bool:
        """Check if drum is in percussion range."""
        return 35 <= self.note_number <= 81


# ═══════════════════════════════════════════════════════════════
# Drum Enum
# ═══════════════════════════════════════════════════════════════

class Drum(Enum):
    """MIDI percussion notes with full metadata."""

    # Bass Drums
    acoustic_bass_drum = DrumValue(35, "Acoustic Bass Drum", "bda", "Kicks")
    bass_drum_1 = DrumValue(36, "Bass Drum 1", "bd1", "Kicks")

    # Snares
    side_stick = DrumValue(37, "Side Stick", "ss", "Snares")
    acoustic_snare = DrumValue(38, "Acoustic Snare", "sna", "Snares")
    hand_clap = DrumValue(39, "Hand Clap", "hcp", "Snares")
    electric_snare = DrumValue(40, "Electric Snare", "sne", "Snares")

    # Toms
    low_floor_tom = DrumValue(41, "Low Floor Tom", "ttfl", "Toms")
    high_floor_tom = DrumValue(43, "High Floor Tom", "ttfh", "Toms")
    low_tom = DrumValue(45, "Low Tom", "ttl", "Toms")
    low_mid_tom = DrumValue(47, "Low-Mid Tom", "ttml", "Toms")
    hi_mid_tom = DrumValue(48, "Hi-Mid Tom", "ttmh", "Toms")
    high_tom = DrumValue(50, "High Tom", "tth", "Toms")

    # Hi-Hats
    closed_hi_hat = DrumValue(42, "Closed Hi-Hat", "hhc", "Hi-Hats")
    pedal_hi_hat = DrumValue(44, "Pedal Hi-Hat", "hhp", "Hi-Hats")
    open_hi_hat = DrumValue(46, "Open Hi-Hat", "hho", "Hi-Hats")

    # Crashes
    crash_cymbal_1 = DrumValue(49, "Crash Cymbal 1", "cr1", "Cymbals")
    crash_cymbal_2 = DrumValue(57, "Crash Cymbal 2", "cr2", "Cymbals")

    # Rides
    ride_cymbal_1 = DrumValue(51, "Ride Cymbal 1", "rd1", "Cymbals")
    ride_bell = DrumValue(53, "Ride Bell", "rdb", "Cymbals")
    ride_cymbal_2 = DrumValue(59, "Ride Cymbal 2", "rd2", "Cymbals")

    # Other Cymbals
    chinese_cymbal = DrumValue(52, "Chinese Cymbal", "chn", "Cymbals")
    splash_cymbal = DrumValue(55, "Splash Cymbal", "spl", "Cymbals")

    # Percussion
    tambourine = DrumValue(54, "Tambourine", "tam", "Percussion")
    cowbell = DrumValue(56, "Cowbell", "cow", "Percussion")
    vibraslap = DrumValue(58, "Vibraslap", "vib", "Percussion")

    # Bongos
    hi_bongo = DrumValue(60, "Hi Bongo", "bgh", "Percussion")
    low_bongo = DrumValue(61, "Low Bongo", "bgl", "Percussion")

    # Congas
    mute_hi_conga = DrumValue(62, "Mute Hi Conga", "cghm", "Percussion")
    open_hi_conga = DrumValue(63, "Open Hi Conga", "cgho", "Percussion")
    low_conga = DrumValue(64, "Low Conga", "cgl", "Percussion")

    # Timbales
    high_timbale = DrumValue(65, "High Timbale", "tbh", "Percussion")
    low_timbale = DrumValue(66, "Low Timbale", "tbl", "Percussion")

    # Agogos
    high_agogo = DrumValue(67, "High Agogo", "agh", "Percussion")
    low_agogo = DrumValue(68, "Low Agogo", "agl", "Percussion")

    # Shakers & Scrapers
    cabasa = DrumValue(69, "Cabasa", "cab", "Percussion")
    maracas = DrumValue(70, "Maracas", "mar", "Percussion")
    short_whistle = DrumValue(71, "Short Whistle", "whs", "Percussion")
    long_whistle = DrumValue(72, "Long Whistle", "whl", "Percussion")
    short_guiro = DrumValue(73, "Short Guiro", "grs", "Percussion")
    long_guiro = DrumValue(74, "Long Guiro", "grl", "Percussion")

    # Claves & Blocks
    claves = DrumValue(75, "Claves", "clv", "Percussion")
    hi_wood_block = DrumValue(76, "Hi Wood Block", "wbh", "Percussion")
    low_wood_block = DrumValue(77, "Low Wood Block", "wbl", "Percussion")

    # Cuica
    mute_cuica = DrumValue(78, "Mute Cuica", "cum", "Percussion")
    open_cuica = DrumValue(79, "Open Cuica", "cuo", "Percussion")

    # Triangle
    mute_triangle = DrumValue(80, "Mute Triangle", "trim", "Percussion")
    open_triangle = DrumValue(81, "Open Triangle", "trio", "Percussion")

    @property
    def note_number(self) -> int:
        """Get MIDI note number."""
        return self.value.note_number

    @property
    def display_name(self) -> str:
        """Get display name."""
        return self.value.display_name

    @property
    def abbreviation(self) -> Optional[str]:
        """Get abbreviation (e.g., 'bda', 'sn')."""
        return self.value.abbreviation

    @property
    def group(self) -> Optional[str]:
        """Get percussion group (Kicks, Snares, etc.)."""
        return self.value.group

    def __int__(self) -> int:
        """Convert to int returns note number."""
        return self.note_number

    @classmethod
    def get(cls, key: str) -> Optional['Drum']:
        """Get drum by name, abbreviation, or note number."""
        # Try by abbreviation
        for drum in cls:
            if drum.abbreviation == key.lower():
                return drum

        # Try by name
        normalized = key.lower().replace(' ', '_').replace('-', '_')
        try:
            return cls[normalized]
        except KeyError:
            pass

        # Try by note number
        if key.isdigit():
            note_num = int(key)
            for drum in cls:
                if drum.note_number == note_num:
                    return drum

        return None

    @classmethod
    def from_note_number(cls, note_number: int) -> Optional['Drum']:
        """Get drum by MIDI note number."""
        for drum in cls:
            if drum.note_number == note_number:
                return drum
        return None

    @classmethod
    def from_abbreviation(cls, abbr: str) -> Optional['Drum']:
        """Get drum by abbreviation."""
        abbr_lower = abbr.lower()
        for drum in cls:
            if drum.abbreviation == abbr_lower:
                return drum
        return None

    @classmethod
    def get_group(cls, group_name: str) -> List['Drum']:
        """Get all drums in a group."""
        group_name = group_name.capitalize()
        return [drum for drum in cls if drum.group == group_name]


# ═══════════════════════════════════════════════════════════════
# Percussion Groups
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class PercussionGroup:
    """Group of percussion instruments."""
    name: str
    drums: Tuple[Drum, ...]

    @property
    def note_numbers(self) -> Tuple[int, ...]:
        """Get all note numbers in this group."""
        return tuple(d.note_number for d in self.drums)

    @property
    def min_note(self) -> int:
        """Minimum note number in group."""
        return min(self.note_numbers)

    @property
    def max_note(self) -> int:
        """Maximum note number in group."""
        return max(self.note_numbers)

    def contains(self, drum: Drum) -> bool:
        """Check if drum is in this group."""
        return drum in self.drums

    def contains_note(self, note_number: int) -> bool:
        """Check if note number is in this group."""
        return note_number in self.note_numbers


class PercussionGroups:
    """All percussion groups organized by family."""

    # Define groups
    KICKS = PercussionGroup(
        "Kicks",
        (Drum.acoustic_bass_drum, Drum.bass_drum_1)
    )

    SNARES = PercussionGroup(
        "Snares",
        (Drum.side_stick, Drum.acoustic_snare, Drum.hand_clap, Drum.electric_snare)
    )

    TOMS = PercussionGroup(
        "Toms",
        (Drum.low_floor_tom, Drum.high_floor_tom, Drum.low_tom,
         Drum.low_mid_tom, Drum.hi_mid_tom, Drum.high_tom)
    )

    HI_HATS = PercussionGroup(
        "Hi-Hats",
        (Drum.closed_hi_hat, Drum.pedal_hi_hat, Drum.open_hi_hat)
    )

    CYMBALS = PercussionGroup(
        "Cymbals",
        (Drum.crash_cymbal_1, Drum.crash_cymbal_2, Drum.ride_cymbal_1,
         Drum.ride_bell, Drum.ride_cymbal_2, Drum.chinese_cymbal, Drum.splash_cymbal)
    )

    PERCUSSION = PercussionGroup(
        "Percussion",
        (Drum.tambourine, Drum.cowbell, Drum.vibraslap, Drum.hi_bongo,
         Drum.low_bongo, Drum.mute_hi_conga, Drum.open_hi_conga, Drum.low_conga,
         Drum.high_timbale, Drum.low_timbale, Drum.high_agogo, Drum.low_agogo,
         Drum.cabasa, Drum.maracas, Drum.short_whistle, Drum.long_whistle,
         Drum.short_guiro, Drum.long_guiro, Drum.claves, Drum.hi_wood_block,
         Drum.low_wood_block, Drum.mute_cuica, Drum.open_cuica, Drum.mute_triangle,
         Drum.open_triangle)
    )

    # All groups for iteration
    ALL_GROUPS = (KICKS, SNARES, TOMS, HI_HATS, CYMBALS, PERCUSSION)

    @classmethod
    def get_group(cls, name: str) -> Optional[PercussionGroup]:
        """Get percussion group by name."""
        name_lower = name.lower()
        for group in cls.ALL_GROUPS:
            if group.name.lower() == name_lower:
                return group
        return None

    @classmethod
    def get_group_for_drum(cls, drum: Drum) -> Optional[PercussionGroup]:
        """Get the group containing a specific drum."""
        for group in cls.ALL_GROUPS:
            if group.contains(drum):
                return group
        return None

    @classmethod
    def get_group_for_note(cls, note_number: int) -> Optional[PercussionGroup]:
        """Get the group containing a specific note number."""
        for group in cls.ALL_GROUPS:
            if group.contains_note(note_number):
                return group
        return None


# ═══════════════════════════════════════════════════════════════
# Singleton Instance
# ═══════════════════════════════════════════════════════════════

DRUM_GROUPS = PercussionGroups()


# ═══════════════════════════════════════════════════════════════
# Convenience Lookup Functions
# ═══════════════════════════════════════════════════════════════

def get_drum(key: str) -> Optional[Drum]:
    """Get drum by name, abbreviation, or note number."""
    return Drum.get(key)


def get_drum_name(note_number: int) -> str:
    """Get drum name by MIDI note number."""
    drum = Drum.from_note_number(note_number)
    return drum.display_name if drum else f"Unknown ({note_number})"


def get_drum_abbreviation(note_number: int) -> Optional[str]:
    """Get drum abbreviation by MIDI note number."""
    drum = Drum.from_note_number(note_number)
    return drum.abbreviation if drum else None


def get_drum_group(note_number: int) -> Optional[str]:
    """Get percussion group name for a note number."""
    drum = Drum.from_note_number(note_number)
    return drum.group if drum else None


def get_group_notes(group_name: str) -> Tuple[int, ...]:
    """Get all note numbers in a percussion group."""
    group = PercussionGroups.get_group(group_name)
    return group.note_numbers if group else ()


# ═══════════════════════════════════════════════════════════════
# Main / Example Usage
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("═" * 60)
    print("MIDI Percussion Examples")
    print("═" * 60)

    # Basic drum lookup
    print("\n🥁 Drum Lookup:")
    kick = Drum.get("bda")
    if kick:
        print(f"  {kick.abbreviation} -> {kick.display_name} (note {kick.note_number})")

    snare = Drum.from_note_number(38)
    if snare:
        print(f"  Note 38 -> {snare.display_name} ({snare.group})")

    # Percussion groups
    print("\n📁 Percussion Groups:")
    for group in PercussionGroups.ALL_GROUPS:
        print(f"  {group.name}: {group.min_note}-{group.max_note} ({len(group.drums)} instruments)")

    # Group details
    print("\n🥁 Kicks Group:")
    kicks = PercussionGroups.KICKS
    for drum in kicks.drums:
        print(f"  {drum.display_name}: {drum.abbreviation} (note {drum.note_number})")

    # Find group for a drum
    drum = Drum.get("hhc")  # Closed hi-hat
    if drum:
        group = PercussionGroups.get_group_for_drum(drum)
        print(f"\n🔍 {drum.display_name} is in the {group.name} group")

    # Get all drums in a group
    print("\n🎵 Hi-Hat notes:")
    hihat_notes = get_group_notes("Hi-Hats")
    print(f"  Note numbers: {hihat_notes}")

    # Drum groups from original format (backward compatible)
    print("\n📦 Original Groups Format:")
    original_groups = {
        'Kicks': PercussionGroups.KICKS.note_numbers,
        'Snares': PercussionGroups.SNARES.note_numbers,
        'Toms': PercussionGroups.TOMS.note_numbers,
        'Hi-Hats': PercussionGroups.HI_HATS.note_numbers,
        'Cymbals': PercussionGroups.CYMBALS.note_numbers,
        'Percussion': PercussionGroups.PERCUSSION.note_numbers,
    }
    for group_name, notes in original_groups.items():
        print(f"  {group_name}: {notes}")

    # Backward compatible dictionaries
    print("\n🔄 Backward Compatible Dictionaries:")

    # Recreate DRUM_NAME_TO_NUMBER
    DRUM_NAME_TO_NUMBER = {drum.display_name: drum.note_number for drum in Drum}
    DRUM_NAME_TO_NUMBER.update({drum.abbreviation: drum.note_number for drum in Drum if drum.abbreviation})

    # Recreate DRUM_NUMBER_TO_NAME
    DRUM_NUMBER_TO_NAME = {drum.note_number: drum.display_name for drum in Drum}

    print(f"  Name to number: {len(DRUM_NAME_TO_NUMBER)} entries")
    print(f"  Number to name: {len(DRUM_NUMBER_TO_NAME)} entries")

    # Example usage
    print("\n✨ Example: Creating a drum pattern")
    pattern = [("bda", 1.0), ("sna", 0.5), ("hhc", 0.25), ("cr1", 2.0)]

    for drum_key, duration in pattern:
        drum = get_drum(drum_key)
        if drum:
            print(f"  {drum.display_name:20} (note {drum.note_number:3}) - duration: {duration} beats")

    print("\n✅ Done!")