# constants.py
"""
Constants for music theory, MIDI, and audio processing.
Grouped by category for better organization and readability.
"""

import pretty_midi

# ==================== MIDI Constants ====================

class MIDI:
    """MIDI-related constants using PrettyMIDI"""
    # MIDI channel constants
    CHANNELS = {
        'PIANO': 0,
        'MELODY': 1,
        'BASS': 2,
        'DRUMS': 9,  # Channel 10 in 1-based indexing
        'PAD': 3,
        'FX': 4
    }

    # MIDI controller numbers (CC)
    CC = {
        'MODULATION': 1,
        'BREATH': 2,
        'FOOT': 4,
        'VOLUME': 7,
        'BALANCE': 8,
        'PAN': 10,
        'EXPRESSION': 11,
        'SUSTAIN': 64,
        'PORTAMENTO': 65,
        'SOSTENUTO': 66,
        'SOFT_PEDAL': 67,
        'LEGATO': 68,
        'HOLD_2': 69,
        'SOUND_VARIATION': 70,
        'TIMBRE': 71,
        'BRIGHTNESS': 74,
        'EFFECTS_DEPTH': 91,
        'REVERB': 91,
        'TREMOLO': 92,
        'CHORUS': 93,
        'DETUNE': 94,
        'PHASER': 95,
        'DATA_INCREMENT': 96,
        'DATA_DECREMENT': 97,
        'ALL_SOUND_OFF': 120,
        'ALL_CONTROLLERS_OFF': 121,
        'LOCAL_CONTROL': 122,
        'ALL_NOTES_OFF': 123
    }

    # PrettyMIDI instrument program access
    @staticmethod
    def get_instrument_program(name: str) -> int:
        """Get GM program number by name using PrettyMIDI constants."""
        return getattr(pretty_midi, name.upper(), 0)

    @staticmethod
    def note_to_number(note_name: str) -> int:
        """Convert note name (e.g., 'C4') to MIDI number."""
        return pretty_midi.note_name_to_number(note_name)

    @staticmethod
    def number_to_note(midi_number: int) -> str:
        """Convert MIDI number to note name."""
        return pretty_midi.note_number_to_name(midi_number)

    @staticmethod
    def number_to_frequency(midi_number: int, tuning: float = 440.0) -> float:
        """Convert MIDI number to frequency in Hz."""
        return pretty_midi.note_number_to_hz(midi_number, tuning)

    # ==================== Volume & Dynamics ====================


class Volume:
    """Volume and dynamics constants (0-127 MIDI range)"""

    # Standard dynamic markings (MIDI velocity values)
    DYNAMICS = {
        'PPP': 16,  # pianississimo - very, very soft
        'PP': 33,  # pianissimo - very soft
        'P': 49,  # piano - soft
        'MP': 64,  # mezzo-piano - moderately soft
        'MF': 80,  # mezzo-forte - moderately loud
        'F': 96,  # forte - loud
        'FF': 112,  # fortissimo - very loud
        'FFF': 127,  # fortississimo - very, very loud
    }

    # Dynamic ranges by instrument family
    INSTRUMENT_DYNAMIC_RANGES = {
        'PIANO': {'min': 30, 'max': 100, 'typical': 70},
        'STRINGS': {'min': 20, 'max': 110, 'typical': 75},
        'WOODWINDS': {'min': 35, 'max': 105, 'typical': 70},
        'BRASS': {'min': 40, 'max': 127, 'typical': 90},
        'PERCUSSION': {'min': 60, 'max': 127, 'typical': 100},
        'VOICE': {'min': 30, 'max': 100, 'typical': 75},
        'SYNTH': {'min': 0, 'max': 127, 'typical': 80}
    }

    # MIDI volume (CC7) ranges
    CC7_VOLUME = {
        'MIN': 0,
        'MAX': 127,
        'DEFAULT': 100,
        'OFF': 0,
        'VERY_SOFT': 20,
        'SOFT': 40,
        'MEDIUM': 70,
        'LOUD': 100,
        'VERY_LOUD': 120
    }

    # MIDI expression (CC11) ranges
    EXPRESSION = {
        'MIN': 0,
        'MAX': 127,
        'DEFAULT': 127,
        'SOFT': 40,
        'MEDIUM': 80,
        'LOUD': 120
    }


# ==================== Articulation Ranges ====================
class Articulation:
    """Articulation parameters and ranges"""

    # Note lengths (in beats or ticks)
    LENGTHS = {
        'WHOLE': 4.0,
        'HALF': 2.0,
        'QUARTER': 1.0,
        'EIGHTH': 0.5,
        'SIXTEENTH': 0.25,
        'THIRTYSECOND': 0.125,

        # Dotted notes
        'DOTTED_WHOLE': 6.0,
        'DOTTED_HALF': 3.0,
        'DOTTED_QUARTER': 1.5,
        'DOTTED_EIGHTH': 0.75,

        # Triplets
        'HALF_TRIPLET': 4 / 3,
        'QUARTER_TRIPLET': 2 / 3,
        'EIGHTH_TRIPLET': 1 / 3,
    }

    # Tempo ranges (BPM)
    TEMPO = {
        'MIN': 20,
        'MAX': 300,
        'DEFAULT': 120,

        # Standard tempo markings
        'LARGO': range(40, 60),
        'LENTO': range(45, 60),
        'ADAGIO': range(60, 70),
        'ANDANTE': range(70, 85),
        'MODERATO': range(85, 100),
        'ALLEGRO': range(100, 130),
        'VIVACE': range(130, 160),
        'PRESTO': range(160, 200),
        'PRESTISSIMO': range(200, 250),
    }

    # Legato/staccato parameters (percentage of note length)
    ARTICULATION_VALUES = {
        'STACCATISSIMO': 0.25,  # very short, 25% of note length
        'STACCATO': 0.5,  # short, 50% of note length
        'MEZZO_STACCATO': 0.75,  # moderately short, 75% of note length
        'PORTATO': 0.8,  # slightly separated
        'TENUTO': 0.95,  # full length
        'LEGATO': 1.0,  # connected, full length
        'MARCATO': 0.6,  # emphasized, slightly short
        'SFORZANDO': 0.9,  # strong accent, full length
    }


# ==================== Pitch & Harmony ====================
class Pitch:
    """Pitch and harmony constants"""

    # Standard tuning
    TUNING = {
        'A4': 440.0,
        'BAROQUE_A4': 415.0,
        'CLASSICAL_A4': 430.0,
        'MODERN_A4': 442.0
    }

    # Interval ratios
    INTERVALS = {
        'UNISON': 1 / 1,
        'MINOR_SECOND': 16 / 15,
        'MAJOR_SECOND': 9 / 8,
        'MINOR_THIRD': 6 / 5,
        'MAJOR_THIRD': 5 / 4,
        'PERFECT_FOURTH': 4 / 3,
        'AUGMENTED_FOURTH': 45 / 32,
        'PERFECT_FIFTH': 3 / 2,
        'MINOR_SIXTH': 8 / 5,
        'MAJOR_SIXTH': 5 / 3,
        'MINOR_SEVENTH': 9 / 5,
        'MAJOR_SEVENTH': 15 / 8,
        'OCTAVE': 2 / 1
    }

    # Pitch bend range (MIDI)
    PITCH_BEND = {
        'MIN': -8192,
        'MAX': 8191,
        'CENTER': 0,
        'SEMITONE_RANGE': 2,  # Default range in semitones
    }


# ==================== Time & Rhythm ====================
class Time:
    """Time and rhythm constants"""

    # Time signatures
    TIME_SIGNATURES = {
        'COMMON': (4, 4),
        'CUT': (2, 2),
        'THREE_FOUR': (3, 4),
        'TWO_FOUR': (2, 4),
        'SIX_EIGHT': (6, 8),
        'NINE_EIGHT': (9, 8),
        'TWELVE_EIGHT': (12, 8),
        'FIVE_FOUR': (5, 4),
        'SEVEN_FOUR': (7, 4),
        'FIVE_EIGHT': (5, 8),
        'SEVEN_EIGHT': (7, 8),
    }

    # PPQ (Pulses Per Quarter note) for MIDI
    PPQ = {
        'STANDARD': 480,
        'HIGH': 960,
        'LOW': 240
    }


# ==================== Audio Processing ====================
class Audio:
    """Audio processing constants"""

    # Sample rates (Hz)
    SAMPLE_RATES = {
        'CD': 44100,
        'DVD': 48000,
        'HIGH': 96000,
        'STUDIO': 192000
    }

    # Bit depths
    BIT_DEPTHS = [16, 24, 32]

    # Audio channels
    CHANNELS = {
        'MONO': 1,
        'STEREO': 2,
        'QUAD': 4,
        'SURROUND_51': 6,
        'SURROUND_71': 8
    }
