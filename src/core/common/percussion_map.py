class PercussionMap:
    """MIDI percussion map with group-based access."""

    # Single dict maps both directions
    _data = {
        # Kicks (35-36)
        'acousticbassdrum': 35, 35: 'acousticbassdrum', 'bda': 35,
        'bassdrum': 36, 36: 'bassdrum', 'bd': 36,

        # Snares (37-40)
        'sidestick': 37, 37: 'sidestick', 'ss': 37,
        'snare': 38, 38: 'snare', 'sn': 38,
        'handclap': 39, 39: 'handclap', 'hc': 39,
        'electricsnare': 40, 40: 'electricsnare', 'sne': 40,

        # Toms (41, 43, 45, 47-48, 50)
        'lowfloortom': 41, 41: 'lowfloortom', 'tomfl': 41,
        'highfloortom': 43, 43: 'highfloortom', 'tomfh': 43,
        'lowtom': 45, 45: 'lowtom', 'toml': 45,
        'lowmidtom': 47, 47: 'lowmidtom', 'tomml': 47,
        'himidtom': 48, 48: 'himidtom', 'tommh': 48,
        'hightom': 50, 50: 'hightom', 'tomh': 50,

        # Hi-Hats (42, 44, 46)
        'closedhihat': 42, 42: 'closedhihat', 'hhc': 42,
        'pedalhihat': 44, 44: 'pedalhihat', 'hhp': 44,
        'openhihat': 46, 46: 'openhihat', 'hho': 46,

        # Cymbals (49, 51-53, 55, 57-59)
        'crashcymbala': 49, 49: 'crashcymbala', 'cymca': 49,
        'ridecymbala': 51, 51: 'ridecymbala', 'cymra': 51,
        'chinesecymbal': 52, 52: 'chinesecymbal', 'cymch': 52,
        'ridebell': 53, 53: 'ridebell', 'rb': 53,
        'splashcymbal': 55, 55: 'splashcymbal', 'cyms': 55,
        'crashcymbalb': 57, 57: 'crashcymbalb', 'cymcb': 57,
        'ridecymbalb': 59, 59: 'ridecymbalb', 'cymrb': 59,

        # Other Percussion (54, 56, 58, 60-81)
        'tambourine': 54, 54: 'tambourine', 'tamb': 54,
        'cowbell': 56, 56: 'cowbell', 'cb': 56,
        'vibraslap': 58, 58: 'vibraslap', 'vibs': 58,
        'hibongo': 60, 60: 'hibongo', 'boh': 60,
        'lobongo': 61, 61: 'lobongo', 'bol': 61,
        'mutehiconga': 62, 62: 'mutehiconga', 'cghm': 62,
        'openhiconga': 63, 63: 'openhiconga', 'cgho': 63,
        'loconga': 64, 64: 'loconga', 'cgl': 64,
        'hitimbale': 65, 65: 'hitimbale', 'timh': 65,
        'lotimbale': 66, 66: 'lotimbale', 'timl': 66,
        'hiagogo': 67, 67: 'hiagogo', 'agh': 67,
        'loagogo': 68, 68: 'loagogo', 'agl': 68,
        'cabasa': 69, 69: 'cabasa', 'cab': 69,
        'maracas': 70, 70: 'maracas', 'mar': 70,
        'shortwhistle': 71, 71: 'shortwhistle', 'whs': 71,
        'longwhistle': 72, 72: 'longwhistle', 'whl': 72,
        'shortguiro': 73, 73: 'shortguiro', 'guis': 73,
        'longguiro': 74, 74: 'longguiro', 'guil': 74,
        'claves': 75, 75: 'claves', 'cl': 75,
        'hiwoodblock': 76, 76: 'hiwoodblock', 'wbh': 76,
        'lowwoodblock': 77, 77: 'lowwoodblock', 'wbl': 77,
        'mutecuica': 78, 78: 'mutecuica', 'cuim': 78,
        'opencuica': 79, 79: 'opencuica', 'cuio': 79,
        'mutetriangle': 80, 80: 'mutetriangle', 'trim': 80,
        'opentriangle': 81, 81: 'opentriangle', 'trio': 81,
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