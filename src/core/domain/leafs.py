from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field, replace
from typing import List, Optional

from core.domain.meta import Meta
from core.domain.point_envelope import Envelope
from core.elements.key_scale_keyscale import KeyScale
from tools import ratio
from tools.ratio import Ratio


# =========================
# Core Part hierarchy
# =========================

@dataclass
class Part(ABC):
    """ Parts are context-free. Composite owns all state. """
    duration: Ratio = ratio.ZERO

    @abstractmethod
    def render(self, time, context) -> Part:
        ...

    def __post_init__(self):
        if self.parent is not None and not isinstance(self.parent, Meta):
            raise TypeError(f"Not a Meta instance, {type(self.parent).__name__}")

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
    accent: Optional[float] = None
    tied: bool = False
    ornament: Optional[str] = None

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
                return value.get(time)
            return value
        _, _, default = PARAM_CONFIG.get(field_name, (None, None, None))
        return default

    def render(self, time: Ratio, context: Meta = None) -> "ResolvedLeaf":
        return ResolvedLeaf(
            pitches      = self.pitches,
            duration     = self.duration,
            key          = self.key or context.get("keyScale") if context else self.key,
            volume       = self._resolve("volume",      context, time),
            articulation = self._resolve("articulation", context, time),
            accent       = self._resolve("accent",      context, time),
            tied         = self.tied,
            ornament     = self.ornament,
        )

    def __repr__(self):
        return f"Leaf({self.pitches}, dur={self.duration}, acc={self.accent}, art={self.articulation})"

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
    accent: float
    tied: bool
    ornament: Optional[str]
    def __repr__(self):
        return (f"ResolvedLeaf({self.pitches}, dur={self.duration}, "
                f"vol={self.volume}, acc={self.accent}, art={self.articulation})")

#=========================
# Algorithm
# =========================


@dataclass
class Algorithm(Part, ABC):

    @abstractmethod
    def _generate(self) -> List[Part]:
        ...

    @abstractmethod
    def render(self, time) -> Part:
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
    accent: Optional[float] = None


@dataclass
class ProgramChange(Event):
    program: int = 0


@dataclass
class ControlChange(Event):
    controller: int = 0
    value: int = 0


# class Articulation(Enum):
#     STACCATISSIMO = 0.05
#     STACCATO      = 0.25
#     NEUTRAL       = 0.5
#     PORTATO       = 0.75
#     LEGATO        = 1.0
#
# class Accent(Enum):
#     NONE = 0.0
#     SOFT = 0.25
#     NORMAL = 0.5
#     MARCATO = 0.75
#     SFORZANDO = 1.0
#
# class Ornament(Enum):
#     TRILL = auto()
#     MORDENT = auto()
#     TURN = auto()
#     TREMOLO = auto()
