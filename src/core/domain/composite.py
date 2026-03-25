# composite.py

from __future__ import annotations

from abc import ABC
from typing import Any, Dict, Iterator, List, Union, Optional

import numpy as np

from core.domain.leafs import Part, ResolvedLeaf
from core.domain.meta import Meta
from core.domain.meta_list import MetaList
from core.domain.params import PARAM_CONFIG
from core.domain.point_envelope import Envelope
from tools.ratio import Ratio

# Fix the recursive type alias
RenderResult = Union[ResolvedLeaf, Iterator[Any], List[Any]]


# =========================
# Composite
# =========================

class Composite(Part, MetaList, ABC):
    """
    A Part that contains child Parts.
    MetaList provides:
      - NumPy-backed child storage and music operations
      - Meta parent-chain lookup for context resolution
    """

    def __init__(self,
                 context: Meta = None,
                 values: Dict[str, Any] = None):
        # Initialize MetaList first (which also initializes SmartList and Meta)
        MetaList.__init__(self,
                          data=[],
                          cycles=False,
                          parent=context)

        # Initialize Part with the context from MetaList
        # Since MetaList inherits from Meta, self can be used as context
        Part.__init__(self, context=self, duration=Ratio(0, 1))

        # Own musical state — envelopes and scalars
        self.update({
            "tempo":        Envelope(),
            "keyScale":     Envelope(),
            "measure":      Envelope(),
            "dynamic":      Envelope(),
            "articulation": Envelope(),
            "panning":      Envelope(),
        })
        if values:
            self.update(values)

    # ------------------------------------------------------------------
    # Context resolution
    # ------------------------------------------------------------------

    def resolve(self, key: str, time: Ratio) -> Any:
        """
        Resolve a key at a given time via the Meta chain.
        Envelopes are evaluated at time; scalars returned directly.
        Falls back to PARAM_CONFIG default.
        """
        if key in self:
            value = self[key]
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
        part.context = self
        self.data = list(self.data)
        self.data.append(part)
        self.data = np.array(self.data, dtype=object)
        self._update_duration(part)

    def get_child(self, idx: int) -> Part:
        return self.data[idx]

    def _update_duration(self, part: Part) -> None:
        self.duration += part.duration

    # ------------------------------------------------------------------
    # Render
    # ------------------------------------------------------------------

    def render(self, time: Ratio) -> Iterator[RenderResult]:
        for child in self.data:
            yield child.render(time)

    # ------------------------------------------------------------------
    # Repr
    # ------------------------------------------------------------------

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(duration={self.duration}, children={len(self.data)})"


# =========================
# Polyphonic
# =========================

class Concurrent(Composite):
    """Children sound simultaneously; duration is the longest child."""

    def _update_duration(self, part: Part) -> None:
        self.duration = max(self.duration, part.duration)

    def render(self, time: Ratio) -> Iterator[RenderResult]:
        # For concurrent rendering, we need to yield all children's results
        # But since they're concurrent, we should perhaps yield them as a list
        # However, to match the return type, we'll yield each child's result
        for child in self.data:
            result = child.render(time)
            if isinstance(result, Iterator):
                yield from result
            elif isinstance(result, list):
                for item in result:
                    yield item
            else:
                yield result
