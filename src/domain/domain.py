# domain.py

from __future__ import annotations
from dataclasses import dataclass, field, replace
from typing import Any, Dict, Iterator, List, Union
from abc import ABC, abstractmethod
from collections import UserList


# =========================
# Primitive musical value
# =========================

@dataclass
class MusicalValue:
    pitch: int
    duration: float
    velocity: int = 100
    articulation: float = 1.0


# =========================
# Core Part hierarchy
# =========================

RenderResult = Union["Part", Iterator["RenderResult"], List["RenderResult"]]


@dataclass
class Part(ABC):
    """
    Parts are context-free. Composite owns all state.
    """
    duration: float = 0.0

    def clone(self) -> "Part":
        return replace(self)

    @abstractmethod
    def render(self) -> RenderResult:
        ...


# =========================
# Composite (Part + UserList + hierarchical state)
# =========================

class Composite(Part, UserList):
    """
    Composite is both a Part and a list of children.
    It also owns hierarchical musical state.
    """

    def __init__(self, values=None, parent=None):
        Part.__init__(self)
        UserList.__init__(self)

        # hierarchical state
        self.values: Dict[str, Any] = values or {}
        self.parent: Composite | None = parent

    # ---- hierarchical state lookup ----

    def get(self, key: str) -> Any:
        if key in self.values:
            return self.values[key]
        if self.parent is not None:
            return self.parent.get(key)
        return None

    def set(self, key: str, value: Any) -> None:
        self.values[key] = value

    def child_state(self, **overrides) -> Dict[str, Any]:
        return {**self.values, **overrides}

    # ---- list behavior ----

    def append(self, part: Part) -> None:
        if not isinstance(part, Part):
            raise TypeError("Only Part instances can be added to a Composite")

        super().append(part)
        self._update_duration_on_append(part)

    def _update_duration_on_append(self, part: Part) -> None:
        pass # overridden by subclasses

    def __repr__(self) -> str:
        cls = self.__class__.__name__
        return f"{cls}(duration={self.duration}, items={len(self.data)})"


# =========================
# Monophonic and Polyphonic
# =========================

class Monophonic(Composite):
    def _update_duration_on_append(self, part: Part) -> None:
        self.duration += part.duration

    def render(self) -> Iterator[RenderResult]:
        for child in self.data:
            yield child.render()


class Polyphonic(Composite):
    def _update_duration_on_append(self, part: Part) -> None:
        self.duration = max(self.duration, part.duration)

    def render(self) -> List[RenderResult]:
        return [child.render() for child in self.data]


# =========================
# Leaf base and events
# =========================

@dataclass
class Leaf(Part, ABC):
    def render(self) -> Part:
        return self


@dataclass
class Event(Leaf, ABC):
    value: MusicalValue | None = None


@dataclass
class Note(Event):
    def __post_init__(self):
        if self.value:
            self.duration = self.value.duration


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

    def render(self) -> Iterator[RenderResult]:
        for part in self._generate():
            yield part.render()


# =========================
# Example algorithm
# =========================

@dataclass
class RepeatedNoteAlgorithm(Algorithm):
    value: MusicalValue = field(default_factory=lambda: MusicalValue(60, 1.0))
    count: int = 4

    def _generate(self) -> List[Part]:
        return [Note(value=self.value) for _ in range(self.count)]


class Pitch:
    pass