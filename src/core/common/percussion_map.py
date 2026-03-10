class PercussionMap:
    """MIDI percussion map with group-based access."""

    # Single dict maps both directions
    _data = {
        # Kicks (35-36)
        'Acoustic Bass Drum': 35, 35: 'Acoustic Bass Drum',
        'Bass Drum 1': 36, 36: 'Bass Drum 1',

        # Snares (37-40)
        'Side Stick': 37, 37: 'Side Stick',
        'Acoustic Snare': 38, 38: 'Acoustic Snare',
        'Hand Clap': 39, 39: 'Hand Clap',
        'Electric Snare': 40, 40: 'Electric Snare',

        # Toms (41, 43, 45, 47-48, 50)
        'Low Floor Tom': 41, 41: 'Low Floor Tom',
        'High Floor Tom': 43, 43: 'High Floor Tom',
        'Low Tom': 45, 45: 'Low Tom',
        'Low-Mid Tom': 47, 47: 'Low-Mid Tom',
        'Hi-Mid Tom': 48, 48: 'Hi-Mid Tom',
        'High Tom': 50, 50: 'High Tom',

        # Hi-Hats (42, 44, 46)
        'Closed Hi-hat': 42, 42: 'Closed Hi-hat',
        'Pedal Hi-hat': 44, 44: 'Pedal Hi-hat',
        'Open Hi-hat': 46, 46: 'Open Hi-hat',

        # Cymbals (49, 51-53, 55, 57-59)
        'Crash Cymbal 1': 49, 49: 'Crash Cymbal 1',
        'Ride Cymbal 1': 51, 51: 'Ride Cymbal 1',
        'Chinese Cymbal': 52, 52: 'Chinese Cymbal',
        'Ride Bell': 53, 53: 'Ride Bell',
        'Splash Cymbal': 55, 55: 'Splash Cymbal',
        'Crash Cymbal 2': 57, 57: 'Crash Cymbal 2',
        'Ride Cymbal 2': 59, 59: 'Ride Cymbal 2',

        # Other Percussion (54, 56, 58, 60-81)
        'Tambourine': 54, 54: 'Tambourine',
        'Cowbell': 56, 56: 'Cowbell',
        'Vibraslap': 58, 58: 'Vibraslap',
        'Hi Bongo': 60, 60: 'Hi Bongo',
        'Low Bongo': 61, 61: 'Low Bongo',
        'Mute Hi Conga': 62, 62: 'Mute Hi Conga',
        'Open Hi Conga': 63, 63: 'Open Hi Conga',
        'Low Conga': 64, 64: 'Low Conga',
        'High Timbale': 65, 65: 'High Timbale',
        'Low Timbale': 66, 66: 'Low Timbale',
        'High Agogo': 67, 67: 'High Agogo',
        'Low Agogo': 68, 68: 'Low Agogo',
        'Cabasa': 69, 69: 'Cabasa',
        'Maracas': 70, 70: 'Maracas',
        'Short Whistle': 71, 71: 'Short Whistle',
        'Long Whistle': 72, 72: 'Long Whistle',
        'Short Guiro': 73, 73: 'Short Guiro',
        'Long Guiro': 74, 74: 'Long Guiro',
        'Claves': 75, 75: 'Claves',
        'Hi Wood Block': 76, 76: 'Hi Wood Block',
        'Low Wood Block': 77, 77: 'Low Wood Block',
        'Mute Cuica': 78, 78: 'Mute Cuica',
        'Open Cuica': 79, 79: 'Open Cuica',
        'Mute Triangle': 80, 80: 'Mute Triangle',
        'Open Triangle': 81, 81: 'Open Triangle',
    }

    # Group definitions with note ranges
    GROUPS = {
        'Kicks': (35, 36),
        'Snares': (37, 40),
        'Toms': (41, 41, 43, 43, 45, 45, 47, 48, 50, 50),  # Discontinuous
        'Hi-Hats': (42, 42, 44, 44, 46, 46),
        'Cymbals': (49, 49, 51, 53, 55, 55, 57, 59),
        'Percussion': (54, 54, 56, 56, 58, 81),  # Everything else
    }

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