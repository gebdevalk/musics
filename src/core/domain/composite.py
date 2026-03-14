# composite.py

from __future__ import annotations
from dataclasses import dataclass, replace
from typing import Any, Dict, Iterator, List, Union, Optional
from abc import ABC, abstractmethod
from collections import UserList

from core.domain.smart_list import SmartList
from tools import ratio
from tools.ratio import Ratio


# =========================
# Primitive musical value
# =========================

# @dataclass
# class MusicalValue:
#     pitch: int
#     duration: Ratio
#     velocity: int = 100
#     articulation: float = 0.9


# =========================
# Core Part hierarchy
# =========================

RenderResult = Union["Part", Iterator["RenderResult"], List["RenderResult"]]


@dataclass
class Part(ABC):
    """
    Parts are context-free. Composite owns all state.
    """
    duration: Ratio = ratio.ONE

    def clone(self) -> "Part":
        return replace(self)

    @abstractmethod
    def render(self, time) -> Part:
        ...

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
# Composite (Part + UserList + hierarchical state)
# =========================

class Composite(Part, SmartList, ABC):
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

    def render(self, time) -> Iterator[RenderResult]:
        for child in self.data:
            yield child.render(time)


class Polyphonic(Composite):
    def _update_duration_on_append(self, part: Part) -> None:
        self.duration = max(self.duration, part.duration)

    def render(self, time) -> List[RenderResult]:
        return [child.render(time) for child in self.data]


# =========================
# Leaf base and events
# =========================

@dataclass
class Leaf(Part, ABC):
    def render(self, time) -> Part:
        return self
