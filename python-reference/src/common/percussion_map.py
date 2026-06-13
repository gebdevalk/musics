from common.data.midi import PERCUSSION_MAP as _pm_data, PERCUSSION_GROUPS as _pm_groups

class PercussionMap:
    """MIDI percussion map with group-based access."""

    _data = _pm_data
    GROUPS = _pm_groups

    @classmethod
    def get(cls, key):
        """Get drum name from note number or note number from name."""
        return cls._data.get(key)

    @classmethod
    def name(cls, note):
        """Get drum name by MIDI note number."""
        return cls._data.get(note)

    @classmethod
    def note(cls, name):
        """Get MIDI note number by drum name."""
        return cls._data.get(name)

    @classmethod
    def contains(cls, key):
        """Check if key exists (either note or name)."""
        return key in cls._data

    @classmethod
    def all_names(cls):
        """Return list of all drum names in note order."""
        return [cls._data[i] for i in range(35, 82) if i in cls._data]

    @classmethod
    def all_notes(cls):
        """Return dict of all drum names to note numbers."""
        return {k: v for k, v in cls._data.items() if isinstance(k, str)}

    @classmethod
    def get_from_group(cls, group_name, index):
        """
        Get MIDI note number from percussion group and index.

        Groups: 'Kicks', 'Snares', 'Toms', 'Hi-Hats', 'Cymbals', 'Percussion'
        """
        # Group to starting note mapping (simplified - first note in each group)
        group_starts = {
            'Kicks': 35,
            'Snares': 37,
            'Toms': 41,
            'Hi-Hats': 42,
            'Cymbals': 49,
            'Percussion': 54
        }

        if group_name not in group_starts:
            return None
        if not 0 <= index <= 7:  # Allow up to 8 per group
            return None

        start = group_starts[group_name]
        return start + index

def main():
    """Demonstrate SoundSet and PercussionMap functionality."""

    # SoundSet examples
    print("=== SoundSet ===")
    print(f"Program 1: {PercussionMap.get(1)}")
    print(f"Flute program: {PercussionMap.get('Flute')}")
    print(f"Contains 'Gunshot'? {PercussionMap.contains('Gunshot')}")
    print()

    # PercussionMap examples
    print("=== PercussionMap ===")
    print(f"Note 36: {PercussionMap.get(36)}")  # 'Bass Drum 1'
    print(f"Note 38: {PercussionMap.get(38)}")  # 'Acoustic Snare'
    print(f"'Closed Hi-hat' note: {PercussionMap.get('Closed Hi-hat')}")  # 42
    print(f"'Open Triangle' note: {PercussionMap.get('Open Triangle')}")  # 81
    print(f"Contains 127? {PercussionMap.contains(127)}")  # False
    print()

    # List some common drums
    print("Common drums:")
    common_notes = [36, 38, 42, 46, 49, 51]
    for note in common_notes:
        print(f"  Note {note}: {PercussionMap.name(note)}")

    print("\nFirst 5 drums in order:")
    for i, name in enumerate(PercussionMap.all_names()[:5]):
        note = 35 + i
        print(f"  Note {note}: {name}")


if __name__ == "__main__":
    main()
