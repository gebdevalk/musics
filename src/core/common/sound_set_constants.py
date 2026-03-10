class SoundSet:
    """Ultra-minimal: one dict handles both lookups."""

    # Single dict maps both directions
    _data = {
        # Piano
        'Acoustic Grand Piano': 1, 1: 'Acoustic Grand Piano',
        'Bright Acoustic Piano': 2, 2: 'Bright Acoustic Piano',
        'Electric Grand Piano': 3, 3: 'Electric Grand Piano',
        'Honky-tonk Piano': 4, 4: 'Honky-tonk Piano',
        'Electric Piano 1': 5, 5: 'Electric Piano 1',
        'Electric Piano 2': 6, 6: 'Electric Piano 2',
        'Harpsichord': 7, 7: 'Harpsichord',
        'Clavinet': 8, 8: 'Clavinet',

        # Chromatic Percussion
        'Celesta': 9, 9: 'Celesta',
        'Glockenspiel': 10, 10: 'Glockenspiel',
        'Music Box': 11, 11: 'Music Box',
        'Vibraphone': 12, 12: 'Vibraphone',
        'Marimba': 13, 13: 'Marimba',
        'Xylophone': 14, 14: 'Xylophone',
        'Tubular Bells': 15, 15: 'Tubular Bells',
        'Dulcimer': 16, 16: 'Dulcimer',

        # Organ
        'Drawbar Organ': 17, 17: 'Drawbar Organ',
        'Percussive Organ': 18, 18: 'Percussive Organ',
        'Rock Organ': 19, 19: 'Rock Organ',
        'Church Organ': 20, 20: 'Church Organ',
        'Reed Organ': 21, 21: 'Reed Organ',
        'Accordion': 22, 22: 'Accordion',
        'Harmonica': 23, 23: 'Harmonica',
        'Tango Accordion': 24, 24: 'Tango Accordion',

        # Guitar
        'Acoustic Guitar (nylon)': 25, 25: 'Acoustic Guitar (nylon)',
        'Acoustic Guitar (steel)': 26, 26: 'Acoustic Guitar (steel)',
        'Electric Guitar (jazz)': 27, 27: 'Electric Guitar (jazz)',
        'Electric Guitar (clean)': 28, 28: 'Electric Guitar (clean)',
        'Electric Guitar (muted)': 29, 29: 'Electric Guitar (muted)',
        'Overdriven Guitar': 30, 30: 'Overdriven Guitar',
        'Distortion Guitar': 31, 31: 'Distortion Guitar',
        'Guitar Harmonics': 32, 32: 'Guitar Harmonics',

        # Bass
        'Acoustic Bass': 33, 33: 'Acoustic Bass',
        'Electric Bass (finger)': 34, 34: 'Electric Bass (finger)',
        'Electric Bass (pick)': 35, 35: 'Electric Bass (pick)',
        'Fretless Bass': 36, 36: 'Fretless Bass',
        'Slap Bass 1': 37, 37: 'Slap Bass 1',
        'Slap Bass 2': 38, 38: 'Slap Bass 2',
        'Synth Bass 1': 39, 39: 'Synth Bass 1',
        'Synth Bass 2': 40, 40: 'Synth Bass 2',

        # Strings
        'Violin': 41, 41: 'Violin',
        'Viola': 42, 42: 'Viola',
        'Cello': 43, 43: 'Cello',
        'Contrabass': 44, 44: 'Contrabass',
        'Tremolo Strings': 45, 45: 'Tremolo Strings',
        'Pizzicato Strings': 46, 46: 'Pizzicato Strings',
        'Orchestral Harp': 47, 47: 'Orchestral Harp',
        'Timpani': 48, 48: 'Timpani',

        # Ensemble
        'String Ensemble 1': 49, 49: 'String Ensemble 1',
        'String Ensemble 2': 50, 50: 'String Ensemble 2',
        'Synth Strings 1': 51, 51: 'Synth Strings 1',
        'Synth Strings 2': 52, 52: 'Synth Strings 2',
        'Choir Aahs': 53, 53: 'Choir Aahs',
        'Voice Oohs': 54, 54: 'Voice Oohs',
        'Synth Choir': 55, 55: 'Synth Choir',
        'Orchestra Hit': 56, 56: 'Orchestra Hit',

        # Brass
        'Trumpet': 57, 57: 'Trumpet',
        'Trombone': 58, 58: 'Trombone',
        'Tuba': 59, 59: 'Tuba',
        'Muted Trumpet': 60, 60: 'Muted Trumpet',
        'French Horn': 61, 61: 'French Horn',
        'Brass Section': 62, 62: 'Brass Section',
        'Synth Brass 1': 63, 63: 'Synth Brass 1',
        'Synth Brass 2': 64, 64: 'Synth Brass 2',

        # Reed
        'Soprano Sax': 65, 65: 'Soprano Sax',
        'Alto Sax': 66, 66: 'Alto Sax',
        'Tenor Sax': 67, 67: 'Tenor Sax',
        'Baritone Sax': 68, 68: 'Baritone Sax',
        'Oboe': 69, 69: 'Oboe',
        'English Horn': 70, 70: 'English Horn',
        'Bassoon': 71, 71: 'Bassoon',
        'Clarinet': 72, 72: 'Clarinet',

        # Pipe
        'Piccolo': 73, 73: 'Piccolo',
        'Flute': 74, 74: 'Flute',
        'Recorder': 75, 75: 'Recorder',
        'Pan Flute': 76, 76: 'Pan Flute',
        'Blown Bottle': 77, 77: 'Blown Bottle',
        'Shakuhachi': 78, 78: 'Shakuhachi',
        'Whistle': 79, 79: 'Whistle',
        'Ocarina': 80, 80: 'Ocarina',

        # Synth Lead
        'Lead 1 (square)': 81, 81: 'Lead 1 (square)',
        'Lead 2 (sawtooth)': 82, 82: 'Lead 2 (sawtooth)',
        'Lead 3 (calliope)': 83, 83: 'Lead 3 (calliope)',
        'Lead 4 (chiff)': 84, 84: 'Lead 4 (chiff)',
        'Lead 5 (charang)': 85, 85: 'Lead 5 (charang)',
        'Lead 6 (voice)': 86, 86: 'Lead 6 (voice)',
        'Lead 7 (fifths)': 87, 87: 'Lead 7 (fifths)',
        'Lead 8 (bass + lead)': 88, 88: 'Lead 8 (bass + lead)',

        # Synth Pad
        'Pad 1 (new age)': 89, 89: 'Pad 1 (new age)',
        'Pad 2 (warm)': 90, 90: 'Pad 2 (warm)',
        'Pad 3 (polysynth)': 91, 91: 'Pad 3 (polysynth)',
        'Pad 4 (choir)': 92, 92: 'Pad 4 (choir)',
        'Pad 5 (bowed)': 93, 93: 'Pad 5 (bowed)',
        'Pad 6 (metallic)': 94, 94: 'Pad 6 (metallic)',
        'Pad 7 (halo)': 95, 95: 'Pad 7 (halo)',
        'Pad 8 (sweep)': 96, 96: 'Pad 8 (sweep)',

        # Synth Effects
        'FX 1 (rain)': 97, 97: 'FX 1 (rain)',
        'FX 2 (soundtrack)': 98, 98: 'FX 2 (soundtrack)',
        'FX 3 (crystal)': 99, 99: 'FX 3 (crystal)',
        'FX 4 (atmosphere)': 100, 100: 'FX 4 (atmosphere)',
        'FX 5 (brightness)': 101, 101: 'FX 5 (brightness)',
        'FX 6 (goblins)': 102, 102: 'FX 6 (goblins)',
        'FX 7 (echoes)': 103, 103: 'FX 7 (echoes)',
        'FX 8 (sci-fi)': 104, 104: 'FX 8 (sci-fi)',

        # Ethnic
        'Sitar': 105, 105: 'Sitar',
        'Banjo': 106, 106: 'Banjo',
        'Shamisen': 107, 107: 'Shamisen',
        'Koto': 108, 108: 'Koto',
        'Kalimba': 109, 109: 'Kalimba',
        'Bagpipe': 110, 110: 'Bagpipe',
        'Fiddle': 111, 111: 'Fiddle',
        'Shanai': 112, 112: 'Shanai',

        # Percussive
        'Tinkle Bell': 113, 113: 'Tinkle Bell',
        'Agogo': 114, 114: 'Agogo',
        'Steel Drums': 115, 115: 'Steel Drums',
        'Woodblock': 116, 116: 'Woodblock',
        'Taiko Drum': 117, 117: 'Taiko Drum',
        'Melodic Tom': 118, 118: 'Melodic Tom',
        'Synth Drum': 119, 119: 'Synth Drum',
        'Reverse Cymbal': 120, 120: 'Reverse Cymbal',

        # Sound Effects
        'Guitar Fret Noise': 121, 121: 'Guitar Fret Noise',
        'Breath Noise': 122, 122: 'Breath Noise',
        'Seashore': 123, 123: 'Seashore',
        'Bird Tweet': 124, 124: 'Bird Tweet',
        'Telephone Ring': 125, 125: 'Telephone Ring',
        'Helicopter': 126, 126: 'Helicopter',
        'Applause': 127, 127: 'Applause',
        'Gunshot': 128, 128: 'Gunshot'
    }

    _groups = {
        'Piano': (1, 8),
        'Chromatic Percussion': (9, 16),
        'Organ': (17, 24),
        'Guitar': (25, 32),
        'Bass': (33, 40),
        'Strings': (41, 48),
        'Ensemble': (49, 56),
        'Brass': (57, 64),
        'Reed': (65, 72),
        'Pipe': (73, 80),
        'Synth Lead': (81, 88),
        'Synth Pad': (89, 96),
        'Synth Effects': (97, 104),
        'Ethnic': (105, 112),
        'Percussive': (113, 120),
        'Sound Effects': (121, 128)
    }

    @classmethod
    def get(cls, key):
        """Get sound name from number or number from sound name."""
        return cls._data.get(key)

    @classmethod
    def name(cls, number):
        """Get sound name by program number."""
        return cls._data.get(number)

    @classmethod
    def number(cls, name):
        """Get program number by sound name."""
        return cls._data.get(name)

    @classmethod
    def contains(cls, key):
        """Check if key exists (either name or number)."""
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
    # main()
    # demo()
    grouped()

