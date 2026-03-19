from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Dict, Any, Iterator, Optional
from enum import Enum, auto

from core.domain.composite import  Composite, Part, RenderResult
from core.elements.key_scale_keyscale import KeyScale
from tools.ratio import Ratio


# =========================
# Leaf base and events
# =========================

# @dataclass
class Leaf(Part):
    """
    This class represents:
    a note, when the list contains one value,
    an interval when the list contains two values,
    a chord when the list contains more the two values,
    or a rest when it contains no values
    """
    duration: Ratio
    pitches: List[int]  # MIDI pitches (can be a chord)
    key: Optional[KeyScale] # used in ornaments
    volume: Optional[float] = None
    articulation: Optional[float] = None
    accent: Optional[float] = None
    tie: Optional[bool] = None
    ornament: str = ""  # ornament name, e.g. "prall", "mordent"

    def copy_with(self, **kwargs) -> "Leaf":
        """Clone with modifications."""
        data = {
            "duration": self.duration,
            "pitches": self.pitches,
            "key": self.key,
            "volume": self.volume,
            "articulation": self.articulation,
            "accent": self.accent,
            "tie": self.tie,
            "ornament": self.ornament,
        }
        data.update(kwargs)
        return Leaf(**data)

    def __repr__(self):
        return f"Leaf({self.pitches}, dur={self.duration}, , acc={self.accent}, , art={self.articulation})"

    def render(self, time) -> Part:
        return self


@dataclass
class NoteOn(Leaf):
    def __init__(self, pitch=None,accent=None, articulation=None, tie=None):
        super().__init__(pitch, None, accent, articulation, accent, tie)


@dataclass
class NoteOff(Leaf):
    def __init__(self, pitch=None):
        super().__init__(pitch, None, None, None,None)


# =========================
# Meta events
# =========================

@dataclass
class Event(Part, ABC):
    pass


@dataclass
class MetaEvent(Event, ABC):
    pass


@dataclass
class ContextChange(MetaEvent):
    def render(self, time) -> Part:
        pass

    changes: Dict[str, Any] = field(default_factory=dict)

    def apply(self, composite: Composite) -> None:
        for k, v in self.changes.items():
            composite.set(k, v)


@dataclass
class ProgramChange(MetaEvent):
    program: int = 0

    def apply(self, composite: Composite) -> None:
        composite.set("program", self.program)


@dataclass
class ControlChange(MetaEvent):
    controller: int = 0
    value: int = 0

    def apply(self, composite: Composite) -> None:
        composite.set(f"cc_{self.controller}", self.value)


# =========================
# Algorithm
# =========================

@dataclass
class Algorithm(Part, ABC):
    @abstractmethod
    def _generate(self) -> List[Part]:
        ...

    def render(self, time) -> Iterator[RenderResult]:
        for part in self._generate():
            yield part.render(time)


"""
Value	LilyPond	Symbol
STACCATO	\staccato or -\.	dot above/below
STACCATISSIMO	\staccatissimo or -\!	wedge
TENUTO	\tenuto or -\_	line
PORTATO	\portato	line + dot
LEGATO	\( \)	slur
MARTELLATO	\marcato or -\^	(overloaded with accent)
BREATH	\breathe	comma
CAESURA	\caesura	double slash
Accent LilyPond markings:

Value	LilyPond	Symbol
ACCENT	\accent or -\>	>
MARCATO	\marcato or -\^	^
SFORZANDO	\sfz	sfz
SFORZATO	\sf	sf
RINFORZANDO	\rfz	rfz
TENUTO_ACCENT	\tenuto \accent	line + >
"""

class Articulation(Enum):
    STACCATISSIMO = 0.05
    STACCATO      = 0.25
    NEUTRAL       = 0.5
    PORTATO       = 0.75
    LEGATO        = 1.0

class Accent(Enum):
    NONE = 0.0
    SOFT = 0.25
    NORMAL = 0.5
    MARCATO = 0.75
    SFORZANDO = 1.0

class Ornament(Enum):
    TRILL = auto()
    MORDENT = auto()
    TURN = auto()
    TREMOLO = auto()
