# leafs.py

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field, replace
from typing import List, Optional

from core.domain.part_meta_score import Part, Meta
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
    volume: Optional[float] = None
    dynamic: Optional[float] = None
    articulation: Optional[float] = None
    transposition: Optional[int] = None
    timbre: Optional[int] = None
    panning: Optional[float | int] = None
    tied: bool = False


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

@dataclass
class LeafOff(Event):
    pitches: List[int] = field(default_factory=list)


