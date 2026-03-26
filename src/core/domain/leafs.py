from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field, replace
from typing import List, Optional, Dict, Any

from core.domain.meta import Meta
from core.domain.params import PARAM_CONFIG
from core.domain.point_envelope import Envelope
from core.elements.key_scale_keyscale import KeyScale, KEYS, SCALES
from core.elements.meter import M44
from core.elements.tempo import Tempo
from midi.constants import Volume
from tools import ratio
from tools.ratio import Ratio


# =========================
# Score — root context
# =========================

class Score(Meta):
    """Root of the Meta parent chain. Holds global musical defaults and the result of parsing."""
    part: Part = None
    def __init__(self, values: Dict[str, Any] = None):
        super().__init__(parent=None, **(values or {}))


SCORE = Score(values={
    "tempo": Tempo(Ratio(1, 4), 92),
    "keyScale": KeyScale(KEYS["C"], SCALES["major"]),
    "measure": M44,
    "dynamic": Volume.DYNAMICS["MF"],
    "articulation": 0.9,
    "panning": 0.0,
})


# =========================
# Core Part hierarchy
# =========================

@dataclass
class Part(ABC):
    """ Parts are context-free. Composite owns all state. """
    duration: Ratio = ratio.ZERO
    context: Meta = field(default_factory=lambda: SCORE)

    @abstractmethod
    def render(self, time) -> Part:
        ...

    def __post_init__(self):
        if self.context is not None and not isinstance(self.context, Meta):
            raise TypeError(f"Not a Meta instance, {type(self.context).__name__}")

    def clone(self) -> "Part":
        return replace(self)

    def nav(self, *indices: int) -> Optional['Part']:
        """
        Navigate using variable number of indices:
        nav() -> self
        nav(0) -> first child
        nav(1,0) -> first child of second child
        """
        current = self
        for idx in indices:
            if current is None:
                return None

            # Try to get child - will fail for leaf nodes
            try:
                # Check if it's a composite by looking for get_child method
                if hasattr(current, 'get_child') and callable(current.get_child):
                    current = current.get_child(idx)
                else:
                    return None
            except (AttributeError, IndexError, TypeError):
                return None
        return current

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
    key: Optional[KeyScale] = None
    volume: Optional[float] = None
    articulation: Optional[float] = None
    dynamic: Optional[float] = None
    panning: Optional[float]|int = 0
    tied: bool = False

    def _resolve(self, field_name: str, context: Meta, time: Ratio):
        """
        Resolution order:
          1. Leaf's own field if set
          2. Context envelope evaluated at time
          3. PARAM_CONFIG default
        """
        local = getattr(self, field_name, None)
        if local is not None:
            return local
        if context is not None and field_name in context:
            value = context[field_name]
            if isinstance(value, Envelope):
                return value.get(float(time))
            return value
        _, _, default = PARAM_CONFIG.get(field_name, (None, None, None))
        return default

    def render(self, time: Ratio) -> "ResolvedLeaf":
        # Get key from context if not set locally
        key = self.key
        if key is None and self.context is not None:
            key = self.context.get("keyScale")

        return ResolvedLeaf(
            pitches=self.pitches,
            duration=self.duration,
            key=key,
            volume=self._resolve("volume", self.context, time),
            articulation=self._resolve("articulation", self.context, time),
            dynamic=self._resolve("dynamic", self.context, time),
            panning=self._resolve("panning", self.context, time),
            tied=self.tied,
        )

    def __repr__(self):
        return f"Leaf({self.pitches}, dur={self.duration}, acc={self.dynamic}, art={self.articulation})"


@dataclass
class ResolvedLeaf:
    """
    Fully resolved, render-time snapshot of a Leaf.
    All fields are concrete — no Nones, no context dependency.
    """
    pitches: List[int]
    duration: Ratio
    key: Optional[KeyScale]
    volume: float
    articulation: float
    dynamic: float
    panning: float
    tied: bool

    def __repr__(self):
        return (f"ResolvedLeaf({self.pitches}, dur={self.duration}, "
                f"vol={self.volume}, acc={self.dynamic}, art={self.articulation})")

# =========================
# Algorithm
# =========================


@dataclass
class Algorithm(Part, ABC):

    @abstractmethod
    def _generate(self) -> List[Part]:
        ...

# =========================
# Meta events
# =========================

@dataclass
class Event(Part, ABC):
    """Events have a zero duration. They represent performance instructions"""
    duration: Ratio = field(default=ratio.ZERO, init=False)


@dataclass
class LeafOff(Event):
    pitches: List[int] = field(default_factory=list)


@dataclass
class LeafOn(Event):
    pitches: List[int] = field(default_factory=list)
    volume: Optional[float] = None
    dynamic: Optional[float] = None


@dataclass
class ProgramChange(Event):
    program: int = 0


@dataclass
class ControlChange(Event):
    controller: int = 0
    value: int = 0
