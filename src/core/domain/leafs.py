from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import List, Dict, Any, Iterator, Optional
from enum import Enum, auto

from core.domain.composite import Leaf, Composite, Part, RenderResult
from tools.ratio import Ratio

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


@dataclass
class Event(Leaf, ABC):
    pass


@dataclass
class Note(Event):
    def __init__(
        self,
        pitch: int,                 # MIDI 0–127
        duration: Ratio = None,      # beats or seconds
        articulation: Optional[Articulation] = None,
        accent: Optional[float] = None,        # 0.0–1.0
        ornaments: Optional[list[Ornament]] = None,
        tie: Optional[bool] = None,
    ) -> None:
        self.pitch = pitch
        self.duration = duration
        self.accent = accent
        self.articulation = articulation
        self.ornaments = ornaments
        self.tie = tie

    # def __post_init__(self):
    #     if self.value:
    #         self.duration = self.value.duration

    def __repr__(self):
        return f"Note(pitch={self.pitch}, octave={self.octave}, duration={self.duration})"


@dataclass
class NoteOn(Note):
    def __init__(self, pitch=None, volume=None, dynamic=None, articulation=None, accent=None,
                 ornaments=None, panning=None, tie=None):
        super().__init__(pitch, None, volume, dynamic, articulation, accent, ornaments, panning, tie)


@dataclass
class NoteOff(Note):
    def __init__(self, pitch=None):
        super().__init__(pitch, None, None, None,
                         None, None, None, None, None)


@dataclass
class Rest(Event):
    def __post_init__(self):
        if self.value:
            self.duration = self.value.duration


@dataclass
class Chord(Event):
    pitches: List[int] = field(default_factory=list)

    def __post_init__(self):
        if self.value:
            self.duration = self.value.duration


# =========================
# Meta events
# =========================

@dataclass
class MetaEvent(Leaf, ABC):
    pass


@dataclass
class ContextChange(MetaEvent):
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
class Algorithm(Leaf, ABC):
    @abstractmethod
    def _generate(self) -> List[Part]:
        ...

    def render(self, time) -> Iterator[RenderResult]:
        for part in self._generate():
            yield part.render(time)
