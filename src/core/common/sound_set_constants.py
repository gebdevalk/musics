class SoundSet:
    """Ultra-minimal: one dict handles both lookups."""

    # Single dict maps both directions, with abbreviations in comments
    _data = {
        # Piano (1-8)
        'acousticGrand': 1, 1: 'acousticGrand', 'AcGd': 1,
        'brightAcoustic': 2, 2: 'brightAcoustic', 'BrAc': 2,
        'electricGrand': 3, 3: 'electricGrand', 'ElGr': 3,
        'honkytonk': 4, 4: 'honkytonk', 'Hnky': 4,
        'electricPiano1': 5, 5: 'electricPiano1', 'EP1': 5,
        'electricPiano2': 6, 6: 'electricPiano2', 'EP2': 6,
        'harpsichord': 7, 7: 'harpsichord', 'Hpsd': 7,
        'clav': 8, 8: 'clav', 'Clav': 8,

        # Chromatic Percussion (9-16)
        'celesta': 9, 9: 'celesta', 'Cel': 9,
        'glockenspiel': 10, 10: 'glockenspiel', 'Glck': 10,
        'musicBox': 11, 11: 'musicBox', 'MBox': 11,
        'vibraphone': 12, 12: 'vibraphone', 'Vib': 12,
        'marimba': 13, 13: 'marimba', 'Mar': 13,
        'xylophone': 14, 14: 'xylophone', 'Xyl': 14,
        'tubularBells': 15, 15: 'tubularBells', 'TbB': 15,
        'dulcimer': 16, 16: 'dulcimer', 'Dul': 16,

        # Organ (17-24)
        'drawbarOrgan': 17, 17: 'drawbarOrgan', 'DrOr': 17,
        'percussiveOrgan': 18, 18: 'percussiveOrgan', 'PcOr': 18,
        'rockOrgan': 19, 19: 'rockOrgan', 'RkOr': 19,
        'churchOrgan': 20, 20: 'churchOrgan', 'ChOr': 20,
        'reedOrgan': 21, 21: 'reedOrgan', 'RdOr': 21,
        'accordion': 22, 22: 'accordion', 'Acc': 22,
        'harmonica': 23, 23: 'harmonica', 'Harm': 23,
        'tangoAccordion': 24, 24: 'tangoAccordion', 'TngAc': 24,

        # Guitar (25-32)
        'acousticGuitarNylon': 25, 25: 'acousticGuitarNylon', 'AcGtN': 25,
        'acousticGuitarSteel': 26, 26: 'acousticGuitarSteel', 'AcGtS': 26,
        'electricGuitarJazz': 27, 27: 'electricGuitarJazz', 'ElGtJ': 27,
        'electricGuitarClean': 28, 28: 'electricGuitarClean', 'ElGtC': 28,
        'electricGuitarMuted': 29, 29: 'electricGuitarMuted', 'ElGtM': 29,
        'overdrivenGuitar': 30, 30: 'overdrivenGuitar', 'OvGt': 30,
        'distortionGuitar': 31, 31: 'distortionGuitar', 'DsGt': 31,
        'guitarHarmonics': 32, 32: 'guitarHarmonics', 'GtHr': 32,

        # Bass (33-40)
        'acousticBass': 33, 33: 'acousticBass', 'AcBs': 33,
        'electricBassFinger': 34, 34: 'electricBassFinger', 'ElBsF': 34,
        'electricBassPick': 35, 35: 'electricBassPick', 'ElBsP': 35,
        'fretlessBass': 36, 36: 'fretlessBass', 'FrBs': 36,
        'slapBass1': 37, 37: 'slapBass1', 'SlB1': 37,
        'slapBass2': 38, 38: 'slapBass2', 'SlB2': 38,
        'synthBass1': 39, 39: 'synthBass1', 'SyB1': 39,
        'synthBass2': 40, 40: 'synthBass2', 'SyB2': 40,

        # Strings (41-48)
        'violin': 41, 41: 'violin', 'Vln': 41,
        'viola': 42, 42: 'viola', 'Vla': 42,
        'cello': 43, 43: 'cello', 'Clo': 43,
        'contrabass': 44, 44: 'contrabass', 'CBs': 44,
        'tremoloStrings': 45, 45: 'tremoloStrings', 'TrSt': 45,
        'pizzicatoStrings': 46, 46: 'pizzicatoStrings', 'PzSt': 46,
        'orchestralHarp': 47, 47: 'orchestralHarp', 'OHp': 47,
        'timpani': 48, 48: 'timpani', 'Tmp': 48,

        # Ensemble (49-56)
        'stringEnsemble1': 49, 49: 'stringEnsemble1', 'StE1': 49,
        'stringEnsemble2': 50, 50: 'stringEnsemble2', 'StE2': 50,
        'synthStrings1': 51, 51: 'synthStrings1', 'SyS1': 51,
        'synthStrings2': 52, 52: 'synthStrings2', 'SyS2': 52,
        'choirAahs': 53, 53: 'choirAahs', 'ChAh': 53,
        'voiceOohs': 54, 54: 'voiceOohs', 'VoOh': 54,
        'synthVoice': 55, 55: 'synthVoice', 'SyVo': 55,
        'orchestraHit': 56, 56: 'orchestraHit', 'OrHt': 56,

        # Brass (57-64)
        'trumpet': 57, 57: 'trumpet', 'Tpt': 57,
        'trombone': 58, 58: 'trombone', 'Tbn': 58,
        'tuba': 59, 59: 'tuba', 'Tba': 59,
        'mutedTrumpet': 60, 60: 'mutedTrumpet', 'MTpt': 60,
        'frenchHorn': 61, 61: 'frenchHorn', 'FHn': 61,
        'brassSection': 62, 62: 'brassSection', 'BrSc': 62,
        'synthBrass1': 63, 63: 'synthBrass1', 'SyBr1': 63,
        'synthBrass2': 64, 64: 'synthBrass2', 'SyBr2': 64,

        # Reed (65-72)
        'sopranoSax': 65, 65: 'sopranoSax', 'SpSx': 65,
        'altoSax': 66, 66: 'altoSax', 'AlSx': 66,
        'tenorSax': 67, 67: 'tenorSax', 'TnSx': 67,
        'baritoneSax': 68, 68: 'baritoneSax', 'BrSx': 68,
        'oboe': 69, 69: 'oboe', 'Ob': 69,
        'englishHorn': 70, 70: 'englishHorn', 'EnHn': 70,
        'bassoon': 71, 71: 'bassoon', 'Bsn': 71,
        'clarinet': 72, 72: 'clarinet', 'Cl': 72,

        # Pipe (73-80)
        'piccolo': 73, 73: 'piccolo', 'Pic': 73,
        'flute': 74, 74: 'flute', 'Fl': 74,
        'recorder': 75, 75: 'recorder', 'Rec': 75,
        'panFlute': 76, 76: 'panFlute', 'PnFl': 76,
        'blownBottle': 77, 77: 'blownBottle', 'BnBt': 77,
        'shakuhachi': 78, 78: 'shakuhachi', 'Skh': 78,
        'whistle': 79, 79: 'whistle', 'Whs': 79,
        'ocarina': 80, 80: 'ocarina', 'Oca': 80,

        # Synth Lead (81-88)
        'lead1': 81, 81: 'lead1', 'Ld1': 81,
        'lead2': 82, 82: 'lead2', 'Ld2': 82,
        'lead3': 83, 83: 'lead3', 'Ld3': 83,
        'lead4': 84, 84: 'lead4', 'Ld4': 84,
        'lead5': 85, 85: 'lead5', 'Ld5': 85,
        'lead6': 86, 86: 'lead6', 'Ld6': 86,
        'lead7': 87, 87: 'lead7', 'Ld7': 87,
        'lead8': 88, 88: 'lead8', 'Ld8': 88,

        # Synth Pad (89-96)
        'pad1': 89, 89: 'pad1', 'Pd1': 89,
        'pad2': 90, 90: 'pad2', 'Pd2': 90,
        'pad3': 91, 91: 'pad3', 'Pd3': 91,
        'pad4': 92, 92: 'pad4', 'Pd4': 92,
        'pad5': 93, 93: 'pad5', 'Pd5': 93,
        'pad6': 94, 94: 'pad6', 'Pd6': 94,
        'pad7': 95, 95: 'pad7', 'Pd7': 95,
        'pad8': 96, 96: 'pad8', 'Pd8': 96,

        # Synth Effects (97-104)
        'fx1': 97, 97: 'fx1', 'FX1': 97,
        'fx2': 98, 98: 'fx2', 'FX2': 98,
        'fx3': 99, 99: 'fx3', 'FX3': 99,
        'fx4': 100, 100: 'fx4', 'FX4': 100,
        'fx5': 101, 101: 'fx5', 'FX5': 101,
        'fx6': 102, 102: 'fx6', 'FX6': 102,
        'fx7': 103, 103: 'fx7', 'FX7': 103,
        'fx8': 104, 104: 'fx8', 'FX8': 104,

        # Ethnic (105-112)
        'sitar': 105, 105: 'sitar', 'Sit': 105,
        'banjo': 106, 106: 'banjo', 'Bnj': 106,
        'shamisen': 107, 107: 'shamisen', 'Smi': 107,
        'koto': 108, 108: 'koto', 'Kot': 108,
        'kalimba': 109, 109: 'kalimba', 'Kmb': 109,
        'bagpipe': 110, 110: 'bagpipe', 'Bgp': 110,
        'fiddle': 111, 111: 'fiddle', 'Fdl': 111,
        'shanai': 112, 112: 'shanai', 'Shn': 112,

        # Percussive (113-120)
        'tinkleBell': 113, 113: 'tinkleBell', 'TnBl': 113,
        'agogo': 114, 114: 'agogo', 'Ago': 114,
        'steelDrums': 115, 115: 'steelDrums', 'StDr': 115,
        'woodblock': 116, 116: 'woodblock', 'WdBl': 116,
        'taikoDrum': 117, 117: 'taikoDrum', 'TkDr': 117,
        'melodicTom': 118, 118: 'melodicTom', 'MlTm': 118,
        'synthDrum': 119, 119: 'synthDrum', 'SyDr': 119,
        'reverseCymbal': 120, 120: 'reverseCymbal', 'RvCy': 120,

        # Sound Effects (121-128)
        'guitarFretNoise': 121, 121: 'guitarFretNoise', 'GtFr': 121,
        'breathNoise': 122, 122: 'breathNoise', 'BrNo': 122,
        'seashore': 123, 123: 'seashore', 'Sea': 123,
        'birdTweet': 124, 124: 'birdTweet', 'BTw': 124,
        'telephoneRing': 125, 125: 'telephoneRing', 'Tel': 125,
        'helicopter': 126, 126: 'helicopter', 'Hel': 126,
        'applause': 127, 127: 'applause', 'Apl': 127,
        'gunshot': 128, 128: 'gunshot', 'GSh': 128
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

