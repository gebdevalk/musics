# src/common/data/midi.py
"""
MIDI reference data — GM instrument map, drum map, CC numbers, and channels.

Pure dict literals. No classes, no methods, no mutation.
Import from here when you need the raw data; the convenience classes
in common.constants, common.sound_set_constants, and common.percussion_map
re-export from these dicts.
"""

# ── MIDI Channels ──────────────────────────────────────────────

MIDI_CHANNELS = {
    'PIANO':   0,
    'MELODY':  1,
    'BASS':    2,
    'DRUMS':   9,   # Channel 10 in 1-based indexing
    'PAD':     3,
    'FX':      4,
}

# ── MIDI Controller Numbers (CC) ───────────────────────────────

MIDI_CC = {
    'MODULATION':           1,
    'BREATH':               2,
    'FOOT':                 4,
    'VOLUME':               7,
    'BALANCE':              8,
    'PAN':                 10,
    'EXPRESSION':          11,
    'SUSTAIN':             64,
    'PORTAMENTO':          65,
    'SOSTENUTO':           66,
    'SOFT_PEDAL':          67,
    'LEGATO':              68,
    'HOLD_2':              69,
    'SOUND_VARIATION':     70,
    'TIMBRE':              71,
    'BRIGHTNESS':          74,
    'EFFECTS_DEPTH':       91,
    'REVERB':              91,
    'TREMOLO':             92,
    'CHORUS':              93,
    'DETUNE':              94,
    'PHASER':              95,
    'DATA_INCREMENT':      96,
    'DATA_DECREMENT':      97,
    'ALL_SOUND_OFF':      120,
    'ALL_CONTROLLERS_OFF': 121,
    'LOCAL_CONTROL':       122,
    'ALL_NOTES_OFF':      123,
}

# ── General MIDI Instrument Map (program 1–128) ────────────────

SOUND_SET = {
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
    'gunshot': 128, 128: 'gunshot', 'GSh': 128,
}

SOUND_SET_GROUPS = {
    'Piano':                (1, 8),
    'Chromatic Percussion': (9, 16),
    'Organ':               (17, 24),
    'Guitar':              (25, 32),
    'Bass':                (33, 40),
    'Strings':             (41, 48),
    'Ensemble':            (49, 56),
    'Brass':               (57, 64),
    'Reed':                (65, 72),
    'Pipe':                (73, 80),
    'Synth Lead':          (81, 88),
    'Synth Pad':           (89, 96),
    'Synth Effects':       (97, 104),
    'Ethnic':             (105, 112),
    'Percussive':         (113, 120),
    'Sound Effects':       (121, 128),
}

# ── GM Percussion Map (note 35–81) ─────────────────────────────

from common.data.drum import PERCUSSION_GROUPS, DRUM_NAME_TO_NUMBER as PERCUSSION_MAP

