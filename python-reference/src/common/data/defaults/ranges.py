# src/common/data/ranges.py
"""
Parameter range definitions — (min, default, max) per parameter.
Source oof truth
"""
from dataclasses import dataclass
from fractions import Fraction
from typing import Generic, TypeVar

T = TypeVar('T')

@dataclass(slots=True, frozen=True)
class Range(Generic[T]):
    min: T
    default: T
    max: T

class Ranges:
    """Container for all Range objects as class attributes."""

    # ═══════════════════════════════════════════════════════════════
    # World (uppercase) ranges
    # ═══════════════════════════════════════════════════════════════
    delay = Range(0.0, 0.0, 2.0)  # Delay amount in seconds
    reverb = Range(0.0, 0.0, 1.0)  # Reverb amount 0.0–1.0
    width = Range(0.0, 0.5, 1.0)  # Stereo width 0.0–1.0

    # ═══════════════════════════════════════════════════════════════
    # Leaf (lowercase) ranges
    # ═══════════════════════════════════════════════════════════════
    articulation = Range(0.2, 0.9, 2.0)  # Note duration multiplier
    bend = Range(-2.0, 0.0, 2.0)  # Pitch bend depth in semitones
    conformity = Range(0.0, 0.0, 1.0)  # Rhythmic/algorithmic conformity
    density = Range(1, 1, 16)  # Subdivisions per beat
    humanization = Range(0.0, 0.0, 1.0)  # Micro-timing randomness
    instrument = Range(0, 0, 127)  # MIDI program number
    micro = Range(-0.5, 0.0, 0.5)  # Micro-timing offset in seconds
    octave = Range(-4, 0, 4)  # Octave shift
    panning = Range(-1.0, 0.0, 1.0)  # Stereo panning
    quant_strength = Range(0.0, 1.0, 1.0)  # Quantization strength
    rate = Range(0.1, 1.0, 10.0)  # Envelope rate scaling
    swing = Range(0.0, 0.0, 1.0)  # Swing ratio
    transposition = Range(-24, 0, 24)  # Semitone transposition
    dur_scale = Range(0.1, 1.0, 4.0)  # Duration scaling multiplier
    volume = Range(0.0, 50.0, 100.0)  # Volume 0–100 scale
    window = Range(0, 0, 64)  # Algorithmic window size

    # ═══════════════════════════════════════════════════════════════
    # Additional ranges
    # ═══════════════════════════════════════════════════════════════
    pitch = Range(0, 60, 127)
    duration = Range(Fraction(0, 1), Fraction(1, 4), Fraction(4, 1))
    dynamic = Range(0, 0, 10)
    accent = Range(0, 0, 10)
    vibrato = Range(0, 0, 127)
    tie = Range(False, False, True)
    channel = Range(0, 0, 15)
    tempo = Range(40, 92, 208)
    key = Range(0, 0, 11)
    effects = Range(0, 0, 10)


# Singleton instance
RANGES = Ranges()

# # ═══════════════════════════════════════════════════════════════
# # Backward-compatible dict (used by meta_display.py, archive/composite.py)
# # ═══════════════════════════════════════════════════════════════
#
# RANGES_MAP = {
#     # ── World ─────────────────────────────────────────────────
#     "Delay":          RANGES.RANGE_DELAY,
#     "Reverb":         RANGES.RANGE_REVERB,
#     "Width":          RANGES.RANGE_WIDTH,
#     # ── Leaf ──────────────────────────────────────────────────
#     "articulation":   RANGES.RANGE_ARTICULATION,
#     "bend":           RANGES.RANGE_BEND,
#     "conformity":     RANGES.RANGE_CONFORMITY,
#     "density":        RANGES.RANGE_DENSITY,
#     "humanization":   RANGES.RANGE_HUMANIZATION,
#     "instrument":     RANGES.RANGE_INSTRUMENT,
#     "micro":          RANGES.RANGE_MICRO,
#     "octave":         RANGES.RANGE_OCTAVE,
#     "panning":        RANGES.RANGE_PANNING,
#     "quantStrength":  RANGES.RANGE_QUANT_STRENGTH,
#     "rate":           RANGES.RANGE_RATE,
#     "swing":          RANGES.RANGE_SWING,
#     "transposition":  RANGES.RANGE_TRANSPOSITION,
#     "durScale":       RANGES.RANGE_DUR_SCALE,
#     "volume":         RANGES.RANGE_VOLUME,
#     "window":         RANGES.RANGE_WINDOW,
#     # ── Legacy (not in context_keys) ──────────────────────────
#     "pitch":          RANGES.RANGE_PITCH,
#     "duration":       RANGES.RANGE_DURATION,
#     "dynamic":        RANGES.RANGE_DYNAMIC,
#     "accent":         RANGES.RANGE_ACCENT,
#     "vibrato":        RANGES.RANGE_VIBRATO,
#     "tie":            RANGES.RANGE_TIE,
#     "channel":        RANGES.RANGE_CHANNEL,
#     "tempo":          RANGES.RANGE_TEMPO,
#     "key":            RANGES.RANGE_KEY,
#     "effects":        RANGES.RANGE_EFFECTS,
# }

if __name__ == '__main__':
    RANGES = Ranges()
    print(RANGES.delay.min)  # 0.0
    print(RANGES.volume.default)  # 50.0