# leafs.py
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Optional

from core.domain.part import Part
from core.domain.context import Context
from tools.ratio import Ratio


# =========================
# Leaf base and events
# =========================


@dataclass
class Leaf(Part):
    """
    A note (1 pitch), interval (2), chord (3+), or rest (0).
    Fields set to None are resolved from context at render time.
    """
    pitches: List[int] = field(default_factory=list)
    dynamic: Optional[float] = None
    articulation: Optional[float] = None
    timbre: Optional[int] = None
    tied: bool = False

    def resolve(self, time: float):
        """
        Resolve missing expressive parameters from context.
        """
        ctx = self.context
        return {
            "pitches": self.pitches,
            "dynamic": self.dynamic if self.dynamic is not None else ctx.value("volume", time),
            "articulation": self.articulation if self.articulation is not None else ctx.value("articulation", time),
            "timbre": self.timbre if self.timbre is not None else ctx.value("timbre", time),
            "tied": self.tied,
        }


from dataclasses import dataclass, field
from tools.ratio import Ratio

# ============================================================
# Tolerant Leaf constructor
# ============================================================

# Save the dataclass-generated __init__
Leaf.__dataclass_init__ = Leaf.__init__

def tolerant_leaf_init(self, *args, **kwargs):
    """
    A tolerant constructor that accepts:
    - pitch=60 or pitches=[60]
    - duration as Ratio, int, or float
    - context=...
    - expressive overrides (dynamic, articulation, timbre, tied)
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
    # 5. Call the dataclass init
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


@dataclass
class DrumLeaf(Part):
    """A drum note."""
    timbre: Optional[int] = None
    dynamic: Optional[float] = None

    def resolve(self, time: float):
        ctx = self.context
        return {
            "timbre": self.timbre if self.timbre is not None else ctx.value("timbre", time),
            "dynamic": self.dynamic if self.dynamic is not None else ctx.value("volume", time),
        }


# =========================
# Algorithm
# =========================

@dataclass
class Algorithm(Part, ABC):

    @abstractmethod
    def generate(self) -> List[Part]:
        """
        Algorithms produce Parts dynamically.
        They inherit context from their parent Part.
        """
        ...


# =========================
# Meta events (performance instructions)
# =========================

@dataclass
class Event(Part):
    """Events have zero duration. They represent performance instructions."""

    def render(self, time: Ratio, context: Optional[Context] = None) -> Part:
        # Base implementation returns a clone
        return self.clone()


@dataclass
class LeafOn(Event):
    pitches: List[int] = field(default_factory=list)
    dynamic: Optional[float] = None
    timbre: Optional[int] = None


@dataclass
class LeafOff(Event):
    pitches: List[int] = field(default_factory=list)
