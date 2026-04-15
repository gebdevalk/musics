# leafs.py
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional

from core.domain.context import Context
from core.domain.part import Part


# ============================================================
# MUSICAL LEAFS
# ============================================================
# These represent *musical events* (notes, chords, rests).
# They are NOT structural nodes and therefore do NOT have IDs.
# They inherit from Part only for:
#   - context inheritance
#   - parent pointer
#   - role propagation
#   - traversal by the performer
#
# Leafs expand into performance events (LeafOn / LeafOff) during rendering.
# ============================================================

@dataclass
class Leaf(Part):
    """
    A musical leaf: note, interval, chord, or rest.

    Semantics:
    - pitches: list of MIDI pitch integers.
      * 0 pitches → rest
      * 1 pitch  → note
      * 2+       → interval or chord

    - dynamic, articulation, timbre:
      Optional expressive parameters.
      If None, they are resolved from the Context at render time.

    - tied:
      Indicates that this leaf is tied to the next one (no NoteOff emitted).
    """
    pitches: List[int] = field(default_factory=list)
    dynamic: Optional[float] = None
    articulation: Optional[float] = None
    timbre: Optional[int] = None
    tied: bool = False

    def resolve(self, time: float):
        """
        Resolve expressive parameters using the Context.

        The performer calls this during rendering to obtain:
        - dynamic
        - articulation
        - timbre

        Missing values are pulled from the Context envelopes.
        """
        ctx = self.context
        return {
            "pitches": self.pitches,
            "dynamic": self.dynamic if self.dynamic is not None else ctx.value("volume", time),
            "articulation": self.articulation if self.articulation is not None else ctx.value("articulation", time),
            "timbre": self.timbre if self.timbre is not None else ctx.value("timbre", time),
            "tied": self.tied,
        }


# ============================================================
# TOLERANT LEAF CONSTRUCTOR
# ============================================================
# This replaces the dataclass __init__ with a musician-friendly API:
#
#   Leaf(pitch=60, duration=1)
#   Leaf(pitches=[60,64,67], duration=Ratio(1,2))
#   Leaf(duration=0.5, dynamic=0.8)
#
# It normalizes:
#   - pitch vs pitches
#   - duration (float/int → Ratio)
#   - expressive parameters
#   - context
#
# After normalization, it calls the dataclass-generated __init__
# and then initializes the Part base class with duration + context.
# ============================================================

from tools.ratio import Ratio

# Save the dataclass-generated __init__
Leaf.__dataclass_init__ = Leaf.__init__

def tolerant_leaf_init(self, *args, **kwargs):
    """
    A tolerant constructor that accepts:
    - pitch=60 or pitches=[60]
    - duration as Ratio, int, or float
    - context=...
    - expressive overrides (dynamic, articulation, timbre, tied)

    This keeps the Leaf API ergonomic for musicians while preserving
    the purity and determinism of the underlying dataclass.
    """

    # --------------------------------------------------------
    # 1. Extract context (belongs to Part)
    # --------------------------------------------------------
    context = kwargs.pop("context", None)

    # --------------------------------------------------------
    # 2. Normalize pitch/pitches
    # --------------------------------------------------------
    if "pitch" in kwargs:
        pitches = [kwargs.pop("pitch")]
    else:
        pitches = kwargs.pop("pitches", [])

    # --------------------------------------------------------
    # 3. Normalize duration
    # --------------------------------------------------------
    dur = kwargs.pop("duration", None)
    if dur is None:
        raise TypeError("Leaf requires a duration")

    if isinstance(dur, Ratio):
        pass
    elif isinstance(dur, int):
        dur = Ratio(dur, 1)
    elif isinstance(dur, float):
        # Convert float to exact rational
        num, den = dur.as_integer_ratio()
        dur = Ratio(num, den)
    else:
        raise TypeError("duration must be Ratio, int, or float")

    # --------------------------------------------------------
    # 4. Expressive parameters
    # --------------------------------------------------------
    dynamic = kwargs.pop("dynamic", None)
    articulation = kwargs.pop("articulation", None)
    timbre = kwargs.pop("timbre", None)
    tied = kwargs.pop("tied", False)

    # --------------------------------------------------------
    # 5. Call the dataclass init for Leaf fields
    # --------------------------------------------------------
    Leaf.__dataclass_init__(
        self,
        pitches=pitches,
        dynamic=dynamic,
        articulation=articulation,
        timbre=timbre,
        tied=tied,
    )

    # --------------------------------------------------------
    # 6. Initialize Part (duration + context)
    # --------------------------------------------------------
    Part.__init__(self, duration=dur, context=context)

# Override the constructor
Leaf.__init__ = tolerant_leaf_init


# ============================================================
# DRUM LEAF
# ============================================================
# A simplified leaf for percussion instruments.
# No pitches: timbre selects the drum sound.
# ============================================================

@dataclass
class DrumLeaf(Part):
    """
    A percussion leaf.
    - timbre selects the drum instrument
    - dynamic controls velocity
    """
    timbre: Optional[int] = None
    dynamic: Optional[float] = None

    def resolve(self, time: float):
        """
        Resolve missing expressive parameters from the Context.
        """
        ctx = self.context
        return {
            "timbre": self.timbre if self.timbre is not None else ctx.value("timbre", time),
            "dynamic": self.dynamic if self.dynamic is not None else ctx.value("volume", time),
        }


# ============================================================
# META EVENTS (PERFORMANCE INSTRUCTIONS)
# ============================================================
# These are zero-duration events emitted by the performer.
# They do NOT represent musical structure or sound by themselves.
#
# LeafOn  → NoteOn event
# LeafOff → NoteOff event
#
# They inherit from Part so they can carry context and be cloned.
# ============================================================

@dataclass
class Event(Part):
    """
    A zero-duration performance instruction.
    The performer emits these during rendering.
    """

    def render(self, time: Ratio, context: Optional[Context] = None) -> Part:
        """
        Base implementation: return a clone.
        Subclasses may override to inject additional behavior.
        """
        return self.clone()


@dataclass
class LeafOn(Event):
    """
    A NoteOn-like event emitted by the performer.
    """
    pitches: List[int] = field(default_factory=list)
    dynamic: Optional[float] = None
    timbre: Optional[int] = None


@dataclass
class LeafOff(Event):
    """
    A NoteOff-like event emitted by the performer.
    """
    pitches: List[int] = field(default_factory=list)
