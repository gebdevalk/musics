from common.data.midi import SOUND_SET, SOUND_SET_GROUPS

class SoundSet:
    """Ultra-minimal: one dict handles both lookups."""

    _data = SOUND_SET
    _groups = SOUND_SET_GROUPS

    @classmethod
    def get(cls, key):
        """Get name from number or number from name (accepts full or short names)."""
        return cls._data.get(key)

    @classmethod
    def name(cls, number, short=False):
        """Get instrument name by program number."""
        if short:
            # Find the short name for this number
            for k, v in cls._data.items():
                if isinstance(k, str) and v == number and k != cls._data[number]:
                    if '(' not in k and len(k) < len(cls._data[number]):  # Heuristic for short names
                        return k
        return cls._data.get(number)

    @classmethod
    def number(cls, name):
        """Get program number by instrument name (accepts full or short)."""
        return cls._data.get(name)

    @classmethod
    def contains(cls, key):
        """Check if key exists (number, full name, or short name)."""
        return key in cls._data

    @classmethod
    def all_names(cls):
        """Return list of all sound names in order."""
        return [cls._data[i] for i in range(1, 129)]

    @classmethod
    def all_numbers(cls):
        """Return dict of all names to numbers."""
        return {k: v for k, v in cls._data.items() if isinstance(k, str)}


    def get_from_group(group_name, index):
        """
        Get MIDI program number from group name and index (0-7).

        Groups: 'Piano', 'Chromatic Percussion', 'Organ', 'Guitar',
                'Bass', 'Strings', 'Ensemble', 'Brass', 'Reed',
                'Pipe', 'Synth Lead', 'Synth Pad', 'Synth Effects',
                'Ethnic', 'Percussive', 'Sound Effects'
        """


        if group_name not in SoundSet._groups:
            return None
        if not 0 <= index <= 7:
            return None

        start, _ = SoundSet._groups[group_name]
        return start + index

    @classmethod
    def get_group(cls, number):
        """Return the group name for a program number."""
        groups = {
            (1,8): 'Piano',
            (9,16): 'Chromatic Percussion',
            (17,24): 'Organ',
            (25,32): 'Guitar',
            (33,40): 'Bass',
            (41,48): 'Strings',
            (49,56): 'Ensemble',
            (57,64): 'Brass',
            (65,72): 'Reed',
            (73,80): 'Pipe',
            (81,88): 'Synth Lead',
            (89,96): 'Synth Pad',
            (97,104): 'Synth Effects',
            (105,112): 'Ethnic',
            (113,120): 'Percussive',
            (121,128): 'Sound Effects'
        }
        for (start, end), group in groups.items():
            if start <= number <= end:
                return group
        return None


def demo():
    # Basic lookups
    print(SoundSet.get(1))  # 'Acoustic Grand Piano'
    print(SoundSet.get('Flute'))  # 74

    # Convenience methods
    print(SoundSet.name(1))  # 'Acoustic Grand Piano'
    print(SoundSet.number('Flute'))  # 74

    # Check existence
    print(SoundSet.contains(128))  # True
    print(SoundSet.contains('Gunshot'))  # True
    print(SoundSet.contains('Fake'))  # False

    # Get all names in order
    for i, name in enumerate(SoundSet.all_names()[:5], 1):
        print(f"{i}: {name}")
    # 1: Acoustic Grand Piano
    # 2: Bright Acoustic Piano
    # 3: Electric Grand Piano
    # 4: Honky-tonk Piano
    # 5: Electric Piano 1

    # Get all name->number mappings
    pianos = {k: v for k, v in SoundSet.all_numbers().items()
              if 'Piano' in k}
    print(pianos)
    # {'Acoustic Grand Piano': 1, 'Bright Acoustic Piano': 2, ...}

def main():
    """Demonstrate SoundSet functionality."""

    # Basic lookups
    print(f"Program 1: {SoundSet.get(1)}")
    print(f"Flute program: {SoundSet.get('Flute')}")
    print()

    # Convenience methods
    print(f"name(1): {SoundSet.name(1)}")
    print(f"number('Flute'): {SoundSet.number('Flute')}")
    print()

    # Check existence
    print(f"Contains 128? {SoundSet.contains(128)}")
    print(f"Contains 'Gunshot'? {SoundSet.contains('Gunshot')}")
    print(f"Contains 'Fake'? {SoundSet.contains('Fake')}")
    print()

    # Get first 5 names in order
    print("First 5 programs:")
    for i, name in enumerate(SoundSet.all_names()[:5], 1):
        print(f"  {i}: {name}")
    print()

    # Get all piano mappings
    pianos = {k: v for k, v in SoundSet.all_numbers().items()
              if 'Piano' in k}
    print("Piano instruments:")
    for name, num in sorted(pianos.items(), key=lambda x: x[1]):
        print(f"  {num}: {name}")

def grouped():
    print(f"Piano index 0: {SoundSet.get_from_group('Piano', 0)}")  # 1
    print(f"Piano index 7: {SoundSet.get_from_group('Piano', 7)}")  # 8
    print(f"Guitar index 2: {SoundSet.get_from_group('Guitar', 2)}")  # 27
    print(f"Brass index 4: {SoundSet.get_from_group('Brass', 4)}")  # 61
    print(f"Sound Effects index 6: {SoundSet.get_from_group('Sound Effects', 6)}")  # 127

if __name__ == "__main__":
    main()
    demo()
    grouped()
