# src/common/data/context_keys.py
"""
ContextKey registry — single source of truth for all context parameters.

Each ContextKey defines:
    name        — canonical long name
    type_       — Python type or domain class
    default     — the default value (must not be None)
    range_      — (min, max, default) for numeric types; None for non-numeric
    description — human-readable
    aliases     — short-name aliases (first is the primary short name)
    category    — "world" (uppercase, overall musical environment)
                  "leaf"  (lowercase, per-event nuance)

Range tuples use (min, max, default) ordering; access default with [-1].

Volume uses a 0–100 scale; the engine maps this to MIDI 0–127 at output time.
"""

from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
from typing import Any

from common.elements.key_scale_keyscale import KEYS, SCALES, KeyScale
from common.elements.meter import Meter, M44
from common.data.tempo import Tempos
from common.data.defaults.ranges import Ranges


# ============================================================
# ContextKey
# ============================================================

@dataclass(frozen=True)
class ContextKey:
    name: str
    type_: type
    default: Any
    description: str
    range_: tuple | None = None          # (min, max, default) or None
    aliases: tuple[str, ...] = ()
    category: str = "leaf"               # "world" | "leaf"

    @property
    def min_val(self):
        """Minimum value (only for numeric types with range_)."""
        if self.range_ is None:
            return None
        return self.range_[0]

    @property
    def max_val(self):
        """Maximum value (only for numeric types with range_)."""
        if self.range_ is None:
            return None
        return self.range_[2]


# ============================================================
# Registry
# ============================================================

_context_keys: dict[str, ContextKey] = {}
_short_to_long: dict[str, str] = {}


def _reg(
    name: str,
    type_: type,
    default: Any,
    description: str,
    range_: tuple | None = None,
    aliases: tuple[str, ...] = (),
    category: str = "leaf",
) -> ContextKey:
    """Register a ContextKey and its aliases. Returns the key."""
    ck = ContextKey(name, type_, default, description, range_, aliases, category)
    _context_keys[name] = ck
    for alias in aliases:
        _context_keys[alias] = ck
        _short_to_long[alias] = name
    return ck


# ============================================================
# UPPERCASE: Musical World
# ============================================================

# Algorithm
A = _reg("Algorithm", str, "",
        "Algorithm name for the performer to resolve at traversal time",
        range_=None, aliases=("A",), category="world")

# Chord / harmonic context
C = _reg("Chord", str, "",
        "Chord symbol or harmonic context (e.g. 'C', 'Am', 'Dm7')",
        range_=None, aliases=("C",), category="world")

# Delay (effect)
D = _reg("Delay", float, 0.0,
        "Delay amount in seconds",
        range_= Ranges.delay.default, aliases=("D",), category="world")

# Form
F = _reg("Form", str, "",
        "Form/section markers (DaCapo, DalSegno, Coda, etc.)",
        range_=None, aliases=("F",), category="world")

# Key — tonic name
K = _reg("Key", str, "C",
        "Tonic key name (e.g. 'C', 'G', 'Bb', 'F#')",
        range_=None, aliases=("K",), category="world")

# Meter
M = _reg("Meter", Meter, M44,
        "Time signature",
        range_=None, aliases=("M",), category="world")

# Orchestration
O = _reg("Orchestration", str, "",
        "Orchestration preset name",
        range_=None, aliases=("O",), category="world")

# Quantization mode
Q = _reg("QuantMode", str, "grid",
        "Quantization mode: 'grid', 'swing', 'none'",
        range_=None, aliases=("Q",), category="world")

# Reverb
R = _reg("Reverb", float, 0.0,
        "Reverb amount 0.0–1.0",
        range_= Ranges.reverb.default, aliases=("R",), category="world")

# Scale — mode name
S = _reg("Scale", str, "major",
        "Scale/mode name (e.g. 'major', 'minor', 'dorian', 'phrygian')",
        range_=None, aliases=("S",), category="world")

# Tempo
T = _reg("Tempo", Tempos, Tempos(Fraction(1, 4), 92),
        "Beats per minute with beat duration",
         range_= Tempos.andante, aliases=("T",), category="world")

# Voice
V = _reg("Voice", str, "",
        "Voice name or selection identifier",
        range_=None, aliases=("V",), category="world")

# Stereo width
W = _reg("Width", float, 0.5,
        "Stereo width 0.0–1.0",
        range_= Ranges.width.default, aliases=("W",), category="world")


# ============================================================
# LOWERCASE: Leaf Nuance
# ============================================================

# articulation: note duration multiplier
a = _reg("articulation", float, 0.9,
        "Note duration multiplier (0.0–2.0). "
        "1.0 = as-written, 0.5 = staccato, 2.0 = over-legato.",
        range_= Ranges.articulation.default, aliases=("a",))

# bend: pitch bend depth
b = _reg("bend", float, 0.0,
        "Pitch bend depth in semitones (-2.0 .. 2.0)",
        range_= Ranges.bend.default, aliases=("b",))

# conformity: rhythmic/algorithmic strictness
c = _reg("conformity", float, 0.0,
        "Rhythmic/algorithmic conformity 0.0–1.0. "
        "0.0 = free, 1.0 = strict.",
        range_= Ranges.conformity.default, aliases=("c",))

# density: subdivisions per beat
d = _reg("density", int, 1,
        "Subdivisions per beat (1 = no subdivision, 2 = eighth notes, etc.)",
        range_= Ranges.density.default, aliases=("d",))

# humanization: micro-timing randomness
h = _reg("humanization", float, 0.0,
        "Micro-timing randomness 0.0–1.0. "
        "0.0 = exact, 1.0 = maximum jitter.",
        range_= Ranges.humanization.default, aliases=("h",))

# instrument: MIDI program number
i = _reg("instrument", int, 0,
        "MIDI program number (0–127). 0 = Acoustic Grand Piano.",
        range_= Ranges.instrument.default, aliases=("i", "timbre", "program", "prog"))

# keyScale: resolved Key + Scale object (derived from K + S)
k = _reg("keyScale", KeyScale, KeyScale(KEYS["C"], SCALES["major"]),
        "Resolved KeyScale object — the combined key and scale. "
        "Derived from K (key name) and S (scale name) at context-creation time.",
        range_=None, aliases=("k",))

# micro: micro-timing offset in seconds
m = _reg("micro", float, 0.0,
        "Micro-timing offset in seconds (per-note timing adjustment)",
        range_= Ranges.micro.default, aliases=("m",))

# octave: octave shift
o = _reg("octave", int, 0,
        "Octave shift in octaves (-3 .. +3)",
        range_= Ranges.octave.default, aliases=("o",))

# panning: stereo position
p = _reg("panning", float, 0.0,
        "Stereo panning -1.0 (hard left) .. +1.0 (hard right)",
        range_= Ranges.panning.default, aliases=("p", "pan"))

# quantStrength: quantization strength
q = _reg("quantStrength", float, 1.0,
        "Quantization strength 0.0–1.0. "
        "0.0 = no quantization, 1.0 = full snap to grid.",
        range_= Ranges.quant_strength.default, aliases=("q",))

# rate: envelope rate scaling
r = _reg("rate", float, 1.0,
        "Envelope rate scaling factor. "
        "1.0 = normal, 0.5 = half-speed, 2.0 = double-speed.",
        range_= Ranges.rate.default, aliases=("r",))

# swing: swing ratio
s = _reg("swing", float, 0.0,
        "Swing ratio 0.0–1.0. 0.0 = straight, 0.5 = swing, 0.66 = shuffle.",
        range_= Ranges.swing.default, aliases=("s",))

# transposition: semitone shift
t = _reg("transposition", int, 0,
        "Semitone transposition (-24 .. +24)",
        range_= Ranges.transposition.default, aliases=("t", "transpose"))

# durScale: duration scaling
u = _reg("durScale", float, 1.0,
        "Duration scaling multiplier. "
        "1.0 = as-written, 0.5 = half duration, 2.0 = double duration.",
        range_= Ranges.dur_scale.default, aliases=("u",))

# volume: 0–100 scale, mapped to MIDI 0–127 by the engine
v = _reg("volume", float, 50.0,
        "Volume 0–100 (mapped to MIDI 0–127 by the engine). "
        "50 = mf, 0 = silence, 100 = ffff.",
        range_= Ranges.volume.default, aliases=("v", "vol"))

# window: algorithmic window size
w = _reg("window", int, 0,
        "Algorithmic window size (0 = no window / full range)",
        range_= Ranges.window.default, aliases=("w",))


# ============================================================
# Public API
# ============================================================

def get(key: str) -> ContextKey:
    """Look up a ContextKey by name or alias. Raises KeyError if unknown."""
    ck = _context_keys.get(key)
    if ck is None:
        raise KeyError(f"Unknown context key: {key!r}")
    return ck


def resolve_name(key: str) -> str:
    """Resolve a short alias to its canonical long name."""
    return get(key).name


def is_known(key: str) -> bool:
    """Check whether a key name or alias is registered."""
    return key in _context_keys


def all_keys() -> dict[str, ContextKey]:
    """Return all registered ContextKeys (canonical names only, no aliases)."""
    return {name: ck for name, ck in _context_keys.items()
            if name == ck.name}


def world_keys() -> dict[str, ContextKey]:
    """Return only uppercase (world) keys."""
    return {n: ck for n, ck in all_keys().items() if ck.category == "world"}


def leaf_keys() -> dict[str, ContextKey]:
    """Return only lowercase (leaf) keys."""
    return {n: ck for n, ck in all_keys().items() if ck.category == "leaf"}


def root_defaults() -> dict[str, Any]:
    """Return the complete set of root-context defaults (canonical name -> default)."""
    return {ck.name: ck.default for ck in all_keys().values()}


# ============================================================
# Resolve derived keyScale at context-creation time
# ============================================================

def resolve_keyScale(key_name: str, scale_name: str) -> KeyScale:
    """
    Resolve K + S into a KeyScale object.
    Call this when building a root context or when K or S changes.
    """
    key_obj = KEYS.get(key_name)
    if key_obj is None:
        raise KeyError(f"Unknown key: {key_name!r}")
    scale_obj = SCALES.get(scale_name)
    if scale_obj is None:
        raise KeyError(f"Unknown scale: {scale_name!r}")
    return KeyScale(key_obj, scale_obj)


# ============================================================
# Volume mapping helper (for engine use)
# ============================================================

def volume_to_midi(vol: float) -> int:
    """Map 0-100 volume to MIDI velocity 0-127, clamped and rounded."""
    return max(0, min(127, round(vol * 1.27)))


# ============================================================
# ROOT_DICT — built from the registry (replaces defaults.py)
# ============================================================

ROOT_DICT: dict[str, Any] = root_defaults()
"""
Complete root-context defaults, built from the ContextKey registry.

Key renames from the old hand-maintained dict:
    Old             New
    measure         Meter
    timbre          instrument
    volume (MIDI)   volume (0-100 scale, mapped to MIDI by engine)
"""


# ============================================================
# Demo
# ============================================================

def _demo():
    print("=== World Keys (Uppercase) ===")
    for name, ck in world_keys().items():
        alias = ck.aliases[0] if ck.aliases else ""
        rng = f"  range={ck.range_}" if ck.range_ else ""
        print(f"  {alias:4s} {name:<20s} default={ck.default!r}{rng}")

    print("\n=== Leaf Keys (Lowercase) ===")
    for name, ck in leaf_keys().items():
        alias = ck.aliases[0] if ck.aliases else ""
        rng = f"  range={ck.range_}" if ck.range_ else ""
        print(f"  {alias:4s} {name:<20s} default={ck.default!r}{rng}")

    print(f"\nTotal: {len(world_keys())} world + {len(leaf_keys())} leaf = "
          f"{len(all_keys())} keys")

    print("\n=== Root Defaults ===")
    for name, val in root_defaults().items():
        print(f"  {name:<20s} = {val!r}")

    print("\n=== Lookup by alias ===")
    for alias in ("v", "T", "a", "p", "k", "i", "K", "S"):
        ck = get(alias)
        print(f"  {alias!r} -> {ck.name}  default={ck.default!r}")

    print("\n=== Volume mapping ===")
    for vol in (0, 25, 50, 75, 100):
        print(f"  volume({vol}) -> MIDI {volume_to_midi(vol)}")


if __name__ == "__main__":
    _demo()
