# composite.py

from __future__ import annotations

from abc import ABC
from typing import Any, Dict, Iterator, List, Union, Optional, Type

import numpy as np

from core.domain.part_meta_score import Part, Meta
from core.domain.params import PARAM_CONFIG
from core.domain.point_envelope import Envelope
from tools.ratio import Ratio, ZERO

RenderResult = Union[Iterator[Any], List[Any]]


# =========================
# Container
# =========================

class Container(Meta, ABC):
    """
    A Part that contains child Parts, with NumPy-backed array operations.

    Always mutable — append at any time. Array ops work on demand and
    return new objects, leaving the original unchanged.
    """

    def __init__(self, parent: Meta = None, values: Dict[str, Any] = None,
                 type_hint: Type = None, cycles: bool = False):
        super().__init__(parent, values)

        self.type = type_hint
        self.cycles = cycles
        self._cycle_pos = 0
        self.data: List[Part] = []

        self.duration = ZERO

        self._state: Dict[str, Any] = {
            "tempo":        Envelope(),
            "keyScale":     Envelope(),
            "measure":      Envelope(),
            "volume":       Envelope(),
            "dynamic":      Envelope(),
            "articulation": Envelope(),
            "panning":      Envelope(),
        }
        if values:
            self._state.update(values)

    # ------------------------------------------------------------------
    # Internal array helper
    # ------------------------------------------------------------------

    def _to_array(self) -> np.ndarray:
        """Convert current data to a NumPy array for array operations."""
        return np.array(self.data, dtype=object)

    def _infer_type(self) -> Type:
        sample = next((x for x in self.data if x is not None), None)
        if sample is None:
            return object
        t = type(sample)
        if issubclass(t, Part):                  return Part
        if issubclass(t, Container):             return Container
        if issubclass(t, str):                   return str
        if issubclass(t, (int, np.integer)):     return int
        if issubclass(t, (float, np.floating)):  return float
        return t

    # ------------------------------------------------------------------
    # Cyclic helpers
    # ------------------------------------------------------------------

    def _wrap(self, idx: int) -> int:
        if len(self.data) == 0:
            raise IndexError("empty list")
        return idx % len(self.data)

    def cycle_next(self):
        item = self.data[self._wrap(self._cycle_pos)]
        self._cycle_pos += 1
        return item

    def cycle_reset(self):
        self._cycle_pos = 0

    # ------------------------------------------------------------------
    # Array operations — always return a new object
    # ------------------------------------------------------------------

    def rotate(self, n: int = 1) -> "Container":
        """Rotate left by n steps."""
        return self._make(np.roll(self._to_array(), -n))

    def reverse(self) -> "Container":
        """Reverse element order (retrograde)."""
        return self._make(np.flip(self._to_array(), axis=0))

    def invert(self, axis: Union[int, tuple, None] = 0) -> "Container":
        """Flip along axis (melodic inversion)."""
        return self._make(np.flip(self._to_array(), axis=axis))

    def transpose(self, semitones: int) -> "Container":
        """Shift all values by semitones. Numeric children only."""
        if self.type not in (float, int):
            raise TypeError(f"transpose requires numeric type, got {self.type.__name__}")
        return self._make(self._to_array() + semitones)

    def reshape(self, *shape) -> "Container":
        return self._make(self._to_array().reshape(*shape))

    def retrograde_inversion(self, axis: Union[int, tuple, None] = 0) -> "Container":
        return self.reverse().invert(axis)

    @property
    def T(self) -> "Container":
        """Matrix transpose."""
        return self._make(self._to_array().T)

    @property
    def flat(self) -> "Container":
        return self._make(self._to_array().flatten())

    # ------------------------------------------------------------------
    # Aggregation (numeric children only)
    # ------------------------------------------------------------------

    def _require_numeric(self):
        if self.type not in (float, int):
            raise TypeError(f"Aggregation requires numeric type, got {self.type.__name__}")

    @property
    def sum(self):
        self._require_numeric(); return self._to_array().sum()

    @property
    def mean(self):
        self._require_numeric(); return self._to_array().mean()

    @property
    def min(self):
        self._require_numeric(); return self._to_array().min()

    @property
    def max(self):
        self._require_numeric(); return self._to_array().max()

    @property
    def std(self):
        self._require_numeric(); return self._to_array().std()

    # ------------------------------------------------------------------
    # Lisp classics
    # ------------------------------------------------------------------

    @property
    def car(self):
        return self.data[0] if len(self.data) > 0 else None

    @property
    def cdr(self) -> "Container":
        if len(self.data) <= 1:
            return None
        return self._make(self._to_array()[1:])

    # ------------------------------------------------------------------
    # Sequence protocol
    # ------------------------------------------------------------------

    def __getitem__(self, idx: int):
        if not isinstance(idx, int):
            raise TypeError(f"Composite indices must be integers, not {type(idx).__name__}")
        if self.cycles:
            idx = self._wrap(idx)
        return self.data[idx]

    def __len__(self):   return len(self.data)
    def __iter__(self):  return iter(self.data)

    # ------------------------------------------------------------------
    # Context resolution
    # ------------------------------------------------------------------

    def resolve(self, key: str, time: Ratio) -> Any:
        value = self.get(key)
        if value is not None:
            if isinstance(value, Envelope):
                if len(value) > 0:
                    return value.get(float(time))
            else:
                return value
        _, _, default = PARAM_CONFIG.get(key, (None, None, None))
        return default

    # ------------------------------------------------------------------
    # Child management
    # ------------------------------------------------------------------

    def append(self, part: Part) -> None:
        if not isinstance(part, Part):
            raise TypeError(f"Expected Part, got {type(part).__name__}")
        part.parent = self
        self.data.append(part)
        self._update_duration(part)

    def extend(self, parts) -> None:
        for part in parts:
            self.append(part)

    def get_child(self, idx: int) -> Part:
        return self.data[idx]

    def _update_duration(self, part: Part) -> None:
        self.duration += part.duration

    # ------------------------------------------------------------------
    # Render
    # ------------------------------------------------------------------

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Iterator[RenderResult]:
        child_context = self if context is None else context
        for child in self.data:
            result = child.render(time, child_context)
            if isinstance(result, Iterator):
                yield from result
            elif isinstance(result, list):
                yield from result
            else:
                yield result

    # ------------------------------------------------------------------
    # Internal factory — subclasses must implement
    # ------------------------------------------------------------------

    def _make(self, data: np.ndarray) -> "Container":
        raise NotImplementedError("Subclasses must implement _make().")
        # obj = Composite.__new__(Composite)
        # obj.data = list(data)
        # obj.type = self.type
        # obj.cycles = self.cycles
        # return obj
    # ------------------------------------------------------------------
    # Printing
    # ------------------------------------------------------------------

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(duration={self.duration}, children={len(self.data)})"

    def _type_tag(self) -> str:
        name  = self.type.__name__ if self.type is not None else "?"
        cycle = " :cyclic" if self.cycles else ""
        return f"{name}{cycle}"

    def to_string(self, compact: bool = False, indent: int = 0) -> str:
        indent_str = " " * indent
        tag = self._type_tag()
        if len(self.data) == 0:
            return f"({tag})"
        is_matrix = isinstance(self.data, np.ndarray) and self.data.ndim > 1
        if not is_matrix:
            elements = [
                item.to_string(compact, indent + 1) if isinstance(item, Container) else str(item)
                for item in self.data
            ]
            if compact:
                return f"({tag} {' '.join(elements)})"
            line = f"({tag} {' '.join(elements)})"
            if len(line) > 60:
                joined = f"\n{indent_str} ".join(elements)
                return f"({tag}\n{indent_str} {joined}\n{indent_str})"
            return line
        if compact:
            return f"({tag} <{self.data.shape}> {self.data.tolist()})"
        lines = [f"({tag}"]
        for row in self.data:
            sub = self._make(np.array(row, dtype=object))
            lines.append(f"{indent_str} {sub.to_string(compact, indent + 1)}")
        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def debug_print(self):
        type_name = self.type.__name__ if self.type else "?"
        print(f"=== {self.__class__.__name__}<{type_name}> | cyclic={self.cycles} ===")
        print(f"  Length : {len(self.data)}")
        print(f"  Compact : {repr(self)}")
        print(f"  Pretty  :\n{self.to_string(compact=False)}")


# =========================
# Composite
# =========================

class Composite(Container):
    """Children sound simultaneously; duration = longest child."""

    def __init__(self, parent: Meta = None, values: Dict[str, Any] = None):
        super().__init__(parent, values)
        self._child_durations: List[Ratio] = []

    def _update_duration(self, part: Part) -> None:
        self._child_durations.append(part.duration)
        self.duration = max(self._child_durations)

    def _make(self, data: np.ndarray) -> "Composite":
        obj = Composite.__new__(Composite)
        obj.data = list(data)
        obj.type = self.type
        obj.cycles = self.cycles
        return obj


# =========================
# Concurrent
# =========================

class Concurrent(Meta):
    """Children sound simultaneously; duration = longest child."""

    def __init__(self, parent: Meta = None, values: Dict[str, Any] = None):
        super().__init__(parent, values)
        self._child_durations: List[Ratio] = []

    def _update_duration(self, part: Part) -> None:
        self._child_durations.append(part.duration)
        self.duration = max(self._child_durations)

