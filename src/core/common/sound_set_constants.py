class SoundSet:
    """Ultra-minimal: one dict handles both lookups."""

    # Single dict maps both directions, with abbreviations in comments
    _data = {
        # Piano (1-8)
        'Acoustic Grand Piano': 1, 1: 'Acoustic Grand Piano', 'Grand Piano': 1,
        'Bright Acoustic Piano': 2, 2: 'Bright Acoustic Piano', 'Brite Piano': 2,
        'Electric Grand Piano': 3, 3: 'Electric Grand Piano', 'E. Grand': 3,
        'Honky-tonk Piano': 4, 4: 'Honky-tonk Piano', 'Honky Tonk': 4,
        'Electric Piano 1': 5, 5: 'Electric Piano 1', 'E. Piano 1': 5,
        'Electric Piano 2': 6, 6: 'Electric Piano 2', 'E. Piano 2': 6,
        'Harpsichord': 7, 7: 'Harpsichord', 'Harpsichord': 7,  # Same
        'Clavinet': 8, 8: 'Clavinet', 'Clavi': 8,

        # Chromatic Percussion (9-16)
        'Celesta': 9, 9: 'Celesta', 'Celesta': 9,
        'Glockenspiel': 10, 10: 'Glockenspiel', 'Glockenspiel': 10,
        'Music Box': 11, 11: 'Music Box', 'Music Box': 11,
        'Vibraphone': 12, 12: 'Vibraphone', 'Vibes': 12,
        'Marimba': 13, 13: 'Marimba', 'Marimba': 13,
        'Xylophone': 14, 14: 'Xylophone', 'Xylophone': 14,
        'Tubular Bells': 15, 15: 'Tubular Bells', 'Tube Bells': 15,
        'Dulcimer': 16, 16: 'Dulcimer', 'Dulcimer': 16,

        # Organ (17-24)
        'Drawbar Organ': 17, 17: 'Drawbar Organ', 'Drawbar Org': 17,
        'Percussive Organ': 18, 18: 'Percussive Organ', 'Perc Organ': 18,
        'Rock Organ': 19, 19: 'Rock Organ', 'Rock Organ': 19,
        'Church Organ': 20, 20: 'Church Organ', 'Church Org': 20,
        'Reed Organ': 21, 21: 'Reed Organ', 'Reed Organ': 21,
        'Accordion': 22, 22: 'Accordion', 'Accordion': 22,
        'Harmonica': 23, 23: 'Harmonica', 'Harmonica': 23,
        'Tango Accordion': 24, 24: 'Tango Accordion', 'Bandoneon': 24,

        # Guitar (25-32)
        'Acoustic Guitar (nylon)': 25, 25: 'Acoustic Guitar (nylon)', 'Nylon Guitar': 25,
        'Acoustic Guitar (steel)': 26, 26: 'Acoustic Guitar (steel)', 'Steel Guitar': 26,
        'Electric Guitar (jazz)': 27, 27: 'Electric Guitar (jazz)', 'Jazz Guitar': 27,
        'Electric Guitar (clean)': 28, 28: 'Electric Guitar (clean)', 'Clean Guitar': 28,
        'Electric Guitar (muted)': 29, 29: 'Electric Guitar (muted)', 'Mute Guitar': 29,
        'Overdriven Guitar': 30, 30: 'Overdriven Guitar', 'Overdrive': 30,
        'Distortion Guitar': 31, 31: 'Distortion Guitar', 'Dist Guitar': 31,
        'Guitar Harmonics': 32, 32: 'Guitar Harmonics', 'Gtr Harmonics': 32,

        # Bass (33-40)
        'Acoustic Bass': 33, 33: 'Acoustic Bass', 'Aco Bass': 33,
        'Electric Bass (finger)': 34, 34: 'Electric Bass (finger)', 'Fingered': 34,
        'Electric Bass (pick)': 35, 35: 'Electric Bass (pick)', 'Pick Bass': 35,
        'Fretless Bass': 36, 36: 'Fretless Bass', 'Fretless': 36,
        'Slap Bass 1': 37, 37: 'Slap Bass 1', 'Slap Bass1': 37,
        'Slap Bass 2': 38, 38: 'Slap Bass 2', 'Slap Bass2': 38,
        'Synth Bass 1': 39, 39: 'Synth Bass 1', 'Syn Bass 1': 39,
        'Synth Bass 2': 40, 40: 'Synth Bass 2', 'Syn Bass 2': 40,

        # Strings (41-48)
        'Violin': 41, 41: 'Violin', 'Violin': 41,
        'Viola': 42, 42: 'Viola', 'Viola': 42,
        'Cello': 43, 43: 'Cello', 'Cello': 43,
        'Contrabass': 44, 44: 'Contrabass', 'Contrabass': 44,
        'Tremolo Strings': 45, 45: 'Tremolo Strings', 'Trem Str': 45,
        'Pizzicato Strings': 46, 46: 'Pizzicato Strings', 'Pizz Str': 46,
        'Orchestral Harp': 47, 47: 'Orchestral Harp', 'Harp': 47,
        'Timpani': 48, 48: 'Timpani', 'Timpani': 48,

        # Ensemble (49-56)
        'String Ensemble 1': 49, 49: 'String Ensemble 1', 'Strings 1': 49,
        'String Ensemble 2': 50, 50: 'String Ensemble 2', 'Strings 2': 50,
        'Synth Strings 1': 51, 51: 'Synth Strings 1', 'Syn Str 1': 51,
        'Synth Strings 2': 52, 52: 'Synth Strings 2', 'Syn Str 2': 52,
        'Choir Aahs': 53, 53: 'Choir Aahs', 'Choir Aahs': 53,
        'Voice Oohs': 54, 54: 'Voice Oohs', 'Voice Oohs': 54,
        'Synth Choir': 55, 55: 'Synth Choir', 'Syn Choir': 55,
        'Orchestra Hit': 56, 56: 'Orchestra Hit', 'Orch Hit': 56,

        # Brass (57-64)
        'Trumpet': 57, 57: 'Trumpet', 'Trumpet': 57,
        'Trombone': 58, 58: 'Trombone', 'Trombone': 58,
        'Tuba': 59, 59: 'Tuba', 'Tuba': 59,
        'Muted Trumpet': 60, 60: 'Muted Trumpet', 'Mute Trumpet': 60,
        'French Horn': 61, 61: 'French Horn', 'F Horn': 61,
        'Brass Section': 62, 62: 'Brass Section', 'Brass Sect': 62,
        'Synth Brass 1': 63, 63: 'Synth Brass 1', 'Syn Brass1': 63,
        'Synth Brass 2': 64, 64: 'Synth Brass 2', 'Syn Brass2': 64,

        # Reed (65-72)
        'Soprano Sax': 65, 65: 'Soprano Sax', 'Soprano Sax': 65,
        'Alto Sax': 66, 66: 'Alto Sax', 'Alto Sax': 66,
        'Tenor Sax': 67, 67: 'Tenor Sax', 'Tenor Sax': 67,
        'Baritone Sax': 68, 68: 'Baritone Sax', 'Bari Sax': 68,
        'Oboe': 69, 69: 'Oboe', 'Oboe': 69,
        'English Horn': 70, 70: 'English Horn', 'English Horn': 70,
        'Bassoon': 71, 71: 'Bassoon', 'Bassoon': 71,
        'Clarinet': 72, 72: 'Clarinet', 'Clarinet': 72,

        # Pipe (73-80)
        'Piccolo': 73, 73: 'Piccolo', 'Piccolo': 73,
        'Flute': 74, 74: 'Flute', 'Flute': 74,
        'Recorder': 75, 75: 'Recorder', 'Recorder': 75,
        'Pan Flute': 76, 76: 'Pan Flute', 'Pan Flute': 76,
        'Blown Bottle': 77, 77: 'Blown Bottle', 'Bottle': 77,
        'Shakuhachi': 78, 78: 'Shakuhachi', 'Shakuhachi': 78,
        'Whistle': 79, 79: 'Whistle', 'Whistle': 79,
        'Ocarina': 80, 80: 'Ocarina', 'Ocarina': 80,

        # Synth Lead (81-88)
        'Lead 1 (square)': 81, 81: 'Lead 1 (square)', 'Square Lead': 81,
        'Lead 2 (sawtooth)': 82, 82: 'Lead 2 (sawtooth)', 'Saw Lead': 82,
        'Lead 3 (calliope)': 83, 83: 'Lead 3 (calliope)', 'Calliope': 83,
        'Lead 4 (chiff)': 84, 84: 'Lead 4 (chiff)', 'Chiff': 84,
        'Lead 5 (charang)': 85, 85: 'Lead 5 (charang)', 'Charang': 85,
        'Lead 6 (voice)': 86, 86: 'Lead 6 (voice)', 'Voice Lead': 86,
        'Lead 7 (fifths)': 87, 87: 'Lead 7 (fifths)', 'Fifths': 87,
        'Lead 8 (bass + lead)': 88, 88: 'Lead 8 (bass + lead)', 'Bass & Lead': 88,

        # Synth Pad (89-96)
        'Pad 1 (new age)': 89, 89: 'Pad 1 (new age)', 'New Age': 89,
        'Pad 2 (warm)': 90, 90: 'Pad 2 (warm)', 'Warm Pad': 90,
        'Pad 3 (polysynth)': 91, 91: 'Pad 3 (polysynth)', 'Polysynth': 91,
        'Pad 4 (choir)': 92, 92: 'Pad 4 (choir)', 'Choir Pad': 92,
        'Pad 5 (bowed)': 93, 93: 'Pad 5 (bowed)', 'Bowed Pad': 93,
        'Pad 6 (metallic)': 94, 94: 'Pad 6 (metallic)', 'Metal Pad': 94,
        'Pad 7 (halo)': 95, 95: 'Pad 7 (halo)', 'Halo Pad': 95,
        'Pad 8 (sweep)': 96, 96: 'Pad 8 (sweep)', 'Sweep Pad': 96,

        # Synth Effects (97-104)
        'FX 1 (rain)': 97, 97: 'FX 1 (rain)', 'Rain': 97,
        'FX 2 (soundtrack)': 98, 98: 'FX 2 (soundtrack)', 'Soundtrack': 98,
        'FX 3 (crystal)': 99, 99: 'FX 3 (crystal)', 'Crystal': 99,
        'FX 4 (atmosphere)': 100, 100: 'FX 4 (atmosphere)', 'Atmosphere': 100,
        'FX 5 (brightness)': 101, 101: 'FX 5 (brightness)', 'Brightness': 101,
        'FX 6 (goblins)': 102, 102: 'FX 6 (goblins)', 'Goblins': 102,
        'FX 7 (echoes)': 103, 103: 'FX 7 (echoes)', 'Echoes': 103,
        'FX 8 (sci-fi)': 104, 104: 'FX 8 (sci-fi)', 'Sci-Fi': 104,

        # Ethnic (105-112)
        'Sitar': 105, 105: 'Sitar', 'Sitar': 105,
        'Banjo': 106, 106: 'Banjo', 'Banjo': 106,
        'Shamisen': 107, 107: 'Shamisen', 'Shamisen': 107,
        'Koto': 108, 108: 'Koto', 'Koto': 108,
        'Kalimba': 109, 109: 'Kalimba', 'Kalimba': 109,
        'Bagpipe': 110, 110: 'Bagpipe', 'Bagpipe': 110,
        'Fiddle': 111, 111: 'Fiddle', 'Fiddle': 111,
        'Shanai': 112, 112: 'Shanai', 'Shanai': 112,

        # Percussive (113-120)
        'Tinkle Bell': 113, 113: 'Tinkle Bell', 'Tinkle Bell': 113,
        'Agogo': 114, 114: 'Agogo', 'Agogo': 114,
        'Steel Drums': 115, 115: 'Steel Drums', 'Steel Drums': 115,
        'Woodblock': 116, 116: 'Woodblock', 'Woodblock': 116,
        'Taiko Drum': 117, 117: 'Taiko Drum', 'Taiko Drum': 117,
        'Melodic Tom': 118, 118: 'Melodic Tom', 'Melodic Tom': 118,
        'Synth Drum': 119, 119: 'Synth Drum', 'Syn Drum': 119,
        'Reverse Cymbal': 120, 120: 'Reverse Cymbal', 'Rev Cymbal': 120,

        # Sound Effects (121-128)
        'Guitar Fret Noise': 121, 121: 'Guitar Fret Noise', 'Fret Noise': 121,
        'Breath Noise': 122, 122: 'Breath Noise', 'Breath': 122,
        'Seashore': 123, 123: 'Seashore', 'Seashore': 123,
        'Bird Tweet': 124, 124: 'Bird Tweet', 'Tweet': 124,
        'Telephone Ring': 125, 125: 'Telephone Ring', 'Telephone': 125,
        'Helicopter': 126, 126: 'Helicopter', 'Helicopter': 126,
        'Applause': 127, 127: 'Applause', 'Applause': 127,
        'Gunshot': 128, 128: 'Gunshot', 'Gunshot': 128
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

