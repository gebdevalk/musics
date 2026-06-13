# constants.py
"""
Constants for music theory, MIDI, and audio processing.
Grouped by category for better organization and readability.
"""

import pretty_midi
from common.data.midi import MIDI_CC, MIDI_CHANNELS

# ==================== MIDI Constants ====================

class MIDI:
    """MIDI-related constants using PrettyMIDI"""
    CHANNELS = MIDI_CHANNELS
    CC = MIDI_CC

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


from common.data.dynamics import DYNAMICS as _DYNAMICS, INSTRUMENT_DYNAMIC_RANGES as _INSTR_RANGES, CC7_VOLUME as _CC7, EXPRESSION as _EXPR

class Volume:
    """Volume and dynamics constants (0-127 MIDI range)"""
    DYNAMICS = _DYNAMICS
    INSTRUMENT_DYNAMIC_RANGES = _INSTR_RANGES
    CC7_VOLUME = _CC7
    EXPRESSION = _EXPR


# ==================== Articulation Ranges ====================
from common.data.tempo import NOTE_LENGTHS as _LENGTHS, TEMPO_RANGES as _TEMPO_RANGES
from common.data.articulation import ARTICULATION_VALUES as _ARTIC_VALS

class Articulation:
    """Articulation parameters and ranges"""
    LENGTHS = _LENGTHS
    TEMPO = _TEMPO_RANGES
    ARTICULATION_VALUES = _ARTIC_VALS


from common.data.pitch import INTERVALS as _INTERVALS, TUNING as _TUNING, PITCH_BEND as _PITCH_BEND

# ==================== Pitch & Harmony ====================
class Pitch:
    """Pitch and harmony constants"""

    TUNING = _TUNING
    INTERVALS = _INTERVALS
    PITCH_BEND = _PITCH_BEND


# ==================== Time & Rhythm ====================
from common.data.meters import TIME_SIGNATURES as _TS, PPQ as _PPQ

class Time:
    """Time and rhythm constants"""
    TIME_SIGNATURES = _TS
    PPQ = _PPQ


# ==================== Audio Processing ====================
from common.data.meters import SAMPLE_RATES as _SR, BIT_DEPTHS as _BD, AUDIO_CHANNELS as _AC

class Audio:
    """Audio processing constants"""
    SAMPLE_RATES = _SR
    BIT_DEPTHS = _BD
    CHANNELS = _AC