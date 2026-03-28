# composite.py

from __future__ import annotations

from abc import ABC
from typing import Any, Dict, Iterator, List, Union, Optional

from core.domain.leafs import Part
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

        # Initialize Part with ZERO duration
        Part.__init__(self, ZERO)

        # Own musical state — envelopes and scalars
        # Store them in a dictionary
        self._state = {
            "tempo": Envelope(),
            "keyScale": Envelope(),
            "measure": Envelope(),
            "volume": Envelope(),
            "dynamic": Envelope(),
            "articulation": Envelope(),
            "panning": Envelope(),
        }
        if values:
            for key, value in values.items():
                self._state[key] = value

    # ------------------------------------------------------------------
    # Context resolution
    # ------------------------------------------------------------------

    def resolve(self, key: str, time: Ratio) -> Any:
        """
        Resolve a key at a given time via the Meta chain.
        Envelopes are evaluated at time; scalars returned directly.
        Falls back to PARAM_CONFIG default.
        """
        # First, try to get the value from the Meta chain (including parents)
        value = self.get(key)

        if value is not None:
            if isinstance(value, Envelope):
                if len(value) > 0:
                    return value.get(float(time))
            else:
                return value

        # Fall back to PARAM_CONFIG default
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
    
    def get(self, key: str) -> Any:
        """
        Get a value from the state, or from the parent chain via MetaList.
        """
        # First, check our own state
        if key in self._state:
            return self._state[key]
        # Then, use the parent's get method
        return super().get(key)

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

    def __init__(self, parent: Meta = None, values: Dict[str, Any] = None):
        super().__init__(parent, values)
        # Track individual durations to find the maximum
        self._child_durations = []

    def append(self, part: Part) -> None:
        if not isinstance(part, Part):
            raise TypeError(f"Expected Part, got {type(part).__name__}")
        
        # Use SmartList's append method to maintain numpy array consistency
        super().append(part)
        self._update_duration(part)

    def _update_duration(self, part: Part) -> None:
        # Track this child's duration
        self._child_durations.append(part.duration)
        # Update total duration to be the maximum of all children
        self.duration = max(self._child_durations)

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
