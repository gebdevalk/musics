# leafs.py

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field, replace
from typing import List, Optional, Dict, Any

from core.domain.meta import Meta
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
    part: Optional[Part] = None  # The main part of the score, if any
    def __init__(self, values: Dict[str, Any] = None):
        super().__init__(parent=None, **(values or {}))


SCORE = Score(values={
    "tempo": Tempo(Ratio(1, 4), 92),
    "keyScale": KeyScale(KEYS["C"], SCALES["major"]),
    "measure": M44,
    "volume": Volume.DYNAMICS["MF"],
    "dynamic": 0,
    "articulation": 0.9,
    "timbre": 0,
    "panning": 0.0,
})


# =========================
# Core Part hierarchy
# =========================

@dataclass
class Part(ABC):
    """ Parts are context-free. Composite owns all state. """
    duration: Ratio = ratio.ZERO

    @abstractmethod
    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        ...

    def __post_init__(self):
        pass

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
    volume: Optional[float] = None
    dynamic: Optional[float] = None
    articulation: Optional[float] = None
    timbre: Optional[int] = None
    panning: Optional[float]|int = 0
    tied: bool = False

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        # Resolve optional fields from parent context
        resolved_leaf = self.clone()
        
        if context is not None:
            # Use context's resolve method to get values at given time
            if resolved_leaf.volume is None:
                resolved_leaf.volume = context.resolve("volume", time)
            if resolved_leaf.dynamic is None:
                resolved_leaf.dynamic = context.resolve("dynamic", time)
            if resolved_leaf.articulation is None:
                resolved_leaf.articulation = context.resolve("articulation", time)
            if resolved_leaf.timbre is None:
                resolved_leaf.timbre = context.resolve("timbre", time)
            if resolved_leaf.panning is None:
                resolved_leaf.panning = context.resolve("panning", time)
        
        return resolved_leaf

# =========================
# Algorithm
# =========================


@dataclass
class Algorithm(Part, ABC):

    @abstractmethod
    def _generate(self) -> List[Part]:
        ...

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        # Algorithm parts need to implement their own render logic
        # For now, return self as a placeholder
        return self.clone()

# =========================
# Meta events
# =========================

@dataclass
class Event(Part):
    """Events have a zero duration. They represent performance instructions"""
    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        # Base implementation returns a clone
        return self.clone()


@dataclass
class LeafOn(Event):
    pitches: List[int] = field(default_factory=list)
    volume: Optional[float] = None
    dynamic: Optional[float] = None
    timbre: Optional[int] = None
    panning: Optional[float] | int = 0

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        resolved_event = self.clone()
        if context is not None:
            if resolved_event.volume is None:
                resolved_event.volume = context.resolve("volume", time)
            if resolved_event.dynamic is None:
                resolved_event.dynamic = context.resolve("dynamic", time)
            if resolved_event.timbre is None:
                resolved_event.timbre = context.resolve("timbre", time)
            if resolved_event.panning is None:
                resolved_event.panning = context.resolve("panning", time)
        return resolved_event

@dataclass
class LeafOff(Event):
    pitches: List[int] = field(default_factory=list)

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        return self.clone()

@dataclass
class ProgramChange(Event):
    program: int = 0

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        return self.clone()

@dataclass
class ControlChange(Event):
    controller: int = 0
    value: int = 0

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Part:
        return self.clone()
