# src/core/domain/parts.py

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from dataclasses import replace
from fractions import Fraction
from functools import reduce
from typing import ClassVar, Optional, Iterator, Any

from core.domain.context import Context

# ── Mutation function for leaf types ──────────────────────────────────────────────────────

def mutate(part: Part, **kwargs) -> Part:
    return replace(part, **kwargs)

def transform(part: Part, *fns) -> Part:
    return reduce(lambda p, f: f(p), fns, part)

transpose  = lambda semitones: lambda part: mutate(part, pitches=tuple(p + semitones for p in part.pitches))
to_triplet = lambda part: mutate(part, duration=part.duration * Fraction(2, 3))
dotted     = lambda part: mutate(part, duration=part.duration * Fraction(3, 2))

# usage
# leaf2 = transform(leaf, transpose(7), to_triplet, dotted)

# ── Immutable leaf types ──────────────────────────────────────────────────────

@dataclass(slots=True, frozen=True)
class Leaf:
    type: ClassVar[str] = "LEAF"
    id: str
    context: Context
    duration: Fraction
    pitches: tuple[int, ...]
    articulation: Optional[float] = None
    dynamic: Optional[int] = None
    modifiers: tuple[tuple[str, str], ...] = ()
    # with what re.findall(MODIFIER, s) naturally returns.
    tied: bool = False

    def __str__(self) -> str:
        return "."

@dataclass(slots=True, frozen=True)
class Rest:
    type: ClassVar[str] = "REST"
    id: str
    context: Context
    duration: Fraction

    def __str__(self) -> str:
        return "r"

@dataclass(slots=True, frozen=True)
class Drum:
    type: ClassVar[str] = "DRUM"
    id: str
    context: Context
    duration: Fraction
    program: int

    def __str__(self) -> str:
        return "x"

# ── Transient operator list is not part of the domain ─────────────────────────

@dataclass(slots=True)
class Transient:
    type: str
    id: str
    context: Context
    children: list[Part] = field(default_factory=list)
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False, compare=False)

    def append(self, part: Any) -> None:
        with self._lock:
            self.children.append(part)

# ── Mutable composite ─────────────────────────────────────────────────────────

@dataclass(slots=True)
class Composite:
    type: str
    id: str
    context: Context
    children: list[Part] = field(default_factory=list)
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False, compare=False)

    @property
    def duration(self) -> Fraction:
        with self._lock:
            return sum((c.duration for c in self.children), Fraction(0))

    def append(self, part: Part) -> None:
        with self._lock:
            self.children.append(part)

    def remove(self, part: Part) -> None:
        with self._lock:
            self.children.remove(part)

    def insert(self, index: int, part: Part) -> None:
        with self._lock:
            self.children.insert(index, part)

    def replace(self, index: int, part: Part) -> Part:
        with self._lock:
            old = self.children[index]
            self.children[index] = part
            return old

    def __iter__(self) -> Iterator[Part]:
        with self._lock:
            snapshot = list(self.children)
        for child in snapshot:
            yield child

    def __len__(self) -> int:
        with self._lock:
            return len(self.children)

    def __str__(self) -> str:
        inner = " ".join(str(c) for c in self)
        match self.type:
            case "SEQ":      return f"[ {inner} ]"
            case "PAR":      return f"<< {inner} >>"
            case "ALGO":     return f"'[ {inner} ]'"
            case "QLIST":    return f"'( {inner} )"
            case "LIST":     return f"( {inner} )"
            case _:          return f"( {inner} )"

# ── Union type Part ────────────────────────────────────────────────────────

Part = Leaf | Rest | Drum | Composite

# ── Mutable leaf types ────────────────────────────────────────────────────────

@dataclass(slots=True)
class MutableRest():
    type: str
    id: str
    context: Context
    duration: Fraction
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False, compare=False)


@dataclass(slots=True)
class MutableLeaf():
    type: str
    id: str
    context: Context
    duration: Fraction
    pitches: list[int]
    articulation: Optional[float] = None
    dynamic: Optional[int] = None
    timbre: Optional[int] = None
    ornament: Optional[str] = None
    tied: bool = False
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False, compare=False)


@dataclass(slots=True)
class MutableDrum():
    type: str
    id: str
    context: Context
    duration: Fraction
    program: int
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False, compare=False)