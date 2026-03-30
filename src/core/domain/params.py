# params.py

from tools.ratio import Ratio

# ── Parameter config: name → (min, max, default) ──────────────────
PARAM_CONFIG = {
    # ── Note ──────────────────────────────────────────────────────
    "pitch": (0, 127, 60),
    "octave": (0, 8, 4),
    "duration": (Ratio(0,1), Ratio(4,1), Ratio(1,4)),
    "volume": (0, 100, 50),
    "dynamic": (0, 10, 0), # beat position related?
    "panning": (-1.0, 0.0, 1.0),
    "articulation": (0.0, 0.9, 4.0),
    "accent": (0, 0, 10),
    # "onset":              (0,    960,  0),
    # "offset":             (0,    960,  480),
    # "attack":             (0,    127,  10),
    # "release":            (0,    127,  10),
    "vibrato": (0, 127, 0),
    "bend": (-100, 100, 0),
    # "ornaments": (0, 4, 0),
    "tie": (False, True, False),
    # "slur":               (0,    1,    0),
    # "tuning":             (-100, 100,  0),
    "channel": (0, 15, 0),
    # ── Beat ──────────────────────────────────────────────────────
    # "beat":               (1,    16,   1),
    # "subdivision":        (1,    8,    1),
    # "tuplet":             (1,    9,    1),
    # ── Measure ───────────────────────────────────────────────────
    # "measure": (1, 999, 1),
    # "time_signature": (0, 10, 0),
    # "meter_numerator": (1, 16, 4),
    # "meter_denominator": (1, 16, 4),
    # ── Section ───────────────────────────────────────────────────
    "tempo": (40, 208, 92),
    "key": (0, 11, 0),
    # "mode": (0, 6, 0),
    # "scale": (0, 10, 0),
    # "chord": (0, 20, 0),
    # "phrase_mark":        (0,    1,    0),
    # "rehearsal_mark":     (0,    20,   0),
    # "crescendo":          (0,    1,    0),
    # "decrescendo":        (0,    1,    0),
    # ── Track ─────────────────────────────────────────────────────
    "instrument": (0, 127, 0),
    # "track":              (0,    15,   0),
    # "clef":               (0,    4,    0),
    "transposition": (-12, 12, 0),
    # "staff":              (1,    4,    1),
    "effects": (0, 10, 0),
    # ── Global ────────────────────────────────────────────────────
    # "genre":              (0,    10,   0),
    # "style":              (0,    10,   0),
    # "form":               (0,    10,   0),
}