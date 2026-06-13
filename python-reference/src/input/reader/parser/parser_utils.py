"""
Parser-local pitch utilities and lookup tables.

Parser-specific data (no canonical equivalent in common/data/):
    INTERVALS       — 7×7 diatonic semitone matrix
    NOTE_TO_INDEX   — diatonic note name → scale-degree index
    ACCIDENTAL_DELTA — accidental string → semitone delta
    Defaults         — INITIAL_PITCH, INITIAL_NAME, DEFAULT_DURATION, DEFAULT_DYNAMIC

Adapter dicts (built from common/data/ canonical sources):
    NOTE_BASE        — derived from data.pitch.NOTE_NAMES_SHARP
    TEMPO_MAP        — derived from data.tempo.TEMPO_RANGES
    DRUM_NAME_MAP    — parser-friendly names → MIDI notes (adapter over data.midi)
    ARTICULATION_MAP — parser syntax symbols → duration multipliers (adapter over data.articulation)

Parser-local lookup tables (no canonical equivalent; documented relationship):
    DYNAMIC_MAP      — hand-tuned volume multipliers (differs from data.dynamics.DYNAMICS)
"""

from common.data.dynamics import DYNAMICS
from common.data.pitch import NOTE_NAME_TO_VALUE
from common.data.defaults.ranges import RANGE_DURATION, RANGE_VOLUME, range_default
from common.data.tempo import TEMPO_RANGES
from common.tools.lowercase_dict import LowercaseDict

# ============================================================
# Parser-specific data (no canonical equivalent)
# ============================================================

NOTE_TO_INDEX = LowercaseDict({
    "c": 0, "d": 1, "e": 2,
    "f": 3, "g": 4, "a": 5, "b": 6,
})

# Semitone deltas from any natural note to any other natural note.
# Row = from note (c..b), column = to note (c..b).
INTERVALS = [
    [+0, +2, +4, +5, -5, -3, -1],  # from c
    [-2, +0, +2, +3, +5, -5, -3],  # from d
    [-4, -2, +0, +1, +3, +5, -5],  # from e
    [-5, -3, -1, +0, +2, +4, +6],  # from f
    [+5, -5, -3, -2, +0, +2, +4],  # from g
    [+3, +5, -5, -4, -2, +0, +2],  # from a
    [+1, +3, +5, -6, -4, -2, +0],  # from b
]

ACCIDENTAL_DELTA = {
    "##": +2,
    "#":  +1,
    "n":   0,
    "b":  -1,
    "bb": -2,
}

# ============================================================
# Parser defaults
# ============================================================

DEFAULT_DURATION = range_default("duration")
DEFAULT_DYNAMIC = range_default("volume")
INITIAL_PITCH = range_default("pitch")
INITIAL_NAME = "c"

# ============================================================
# NOTE_BASE — derived from data.pitch.NOTE_NAME_TO_VALUE
#   Maps natural note name → MIDI pitch-class (C=0..B=11).
# ============================================================

NOTE_BASE = LowercaseDict(NOTE_NAME_TO_VALUE)

# ============================================================
# DYNAMIC_MAP — derived from data.dynamics.DYNAMICS
#   Lowercase keys via LowercaseDict, values scaled 0–100 → 0.0–1.0.
#   Excludes 'SILENCE'.
# ============================================================

DYNAMIC_MAP = LowercaseDict({
    k.lower(): v / 100.0
    for k, v in DYNAMICS.items()
    if k != "SILENCE"
})

# ============================================================
# TEMPO_MAP — derived from data.tempo.TEMPO_RANGES
#   Title-case keys, (min, max) BPM tuples only.
#   Filters out scalar entries like MIN, MAX, DEFAULT.
# ============================================================

TEMPO_MAP = {
    k.title(): v
    for k, v in TEMPO_RANGES.items()
    if isinstance(v, tuple)
}


