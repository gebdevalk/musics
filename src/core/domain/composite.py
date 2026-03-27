# composite.py

from __future__ import annotations

from abc import ABC
from typing import Any, Dict, Iterator, List, Union, Optional

import numpy as np

from core.domain.leafs import Part, SCORE
from core.domain.meta import Meta
from core.domain.meta_list import MetaList
from core.domain.params import PARAM_CONFIG
from core.domain.point_envelope import Envelope
from tools.ratio import Ratio, ZERO

# Fix the recursive type alias
RenderResult = Union[Iterator[Any], List[Any]]


# =========================
# Composite
# =========================

class Composite(MetaList, Part, ABC):
    """
    A Part that contains child Parts.
    MetaList provides:
      - NumPy-backed child storage and music operations
      - Meta parent-chain lookup for context resolution
    """

    def __init__(self, parent: Meta = None, values: Dict[str, Any] = None):
        # Initialize MetaList with the provided parent
        MetaList.__init__(self, data=[], cycles=False, parent=parent)

        # Initialize Part (no context needed)
        Part.__init__(self, ZERO)

        # Own musical state — envelopes and scalars
        self.update({
            "tempo": Envelope(),
            "keyScale": Envelope(),
            "measure": Envelope(),
            "volume": Envelope(),
            "dynamic": Envelope(),
            "articulation": Envelope(),
            "panning": Envelope(),
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
        
        # NO parent setting - parts are context-free
        # Use SmartList's append method to maintain numpy array consistency
        super().append(part)
        self._update_duration(part)

    def get_child(self, idx: int) -> Part:
        return self.data[idx]

    def _update_duration(self, part: Part) -> None:
        self.duration += part.duration

    # ------------------------------------------------------------------
    # Render
    # ------------------------------------------------------------------

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Iterator[RenderResult]:
        # Use self as context for children (since Composite is a Meta)
        child_context = self if context is None else context
        
        for child in self.data:
            result = child.render(time, child_context)
            if isinstance(result, Iterator):
                yield from result
            elif isinstance(result, list):
                for item in result:
                    yield item
            else:
                yield result

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

    def render(self, time: Ratio, context: Optional[Meta] = None) -> Iterator[RenderResult]:
        # Use self as context for children
        child_context = self if context is None else context
        
        # For concurrent rendering, all children share the same time
        for child in self.data:
            result = child.render(time, child_context)
            if isinstance(result, Iterator):
                yield from result
            elif isinstance(result, list):
                for item in result:
                    yield item
            else:
                yield result
