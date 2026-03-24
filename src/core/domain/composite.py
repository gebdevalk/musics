# composite.py

from __future__ import annotations

from abc import ABC
from typing import Any, Dict, Iterator, List, Union, Optional

from core.domain.leafs import Part, ResolvedLeaf
from core.domain.meta import Meta
from core.domain.meta_list import MetaList, ListType
from core.domain.params import PARAM_CONFIG
from core.domain.point_envelope import Envelope
from core.elements.key_scale_keyscale import KeyScale, KEYS, SCALES
from core.elements.meter import M44
from core.elements.tempo import Tempo
from midi.constants import Volume
from tools import ratio
from tools.ratio import Ratio

RenderResult = Union[ResolvedLeaf, Iterator["RenderResult"], List["RenderResult"]]


# =========================
# Score — root context
# =========================

class Score(Meta):
    """Root of the Meta parent chain. Holds global musical defaults."""
    def __init__(self, values: Dict[str, Any] = None):
        super().__init__(parent=None, **(values or {}))


SCORE = Score(values={
    "tempo":        Tempo(Ratio(1, 4), 92),
    "keyScale":     KeyScale(KEYS["C"], SCALES["major"]),
    "measure":      M44,
    "dynamic":      Volume.DYNAMICS["MF"],
    "articulation": 0.9,
    "panning":      0.0,
})


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
                 values: Dict[str, Any] = None,
                 parent: "Composite" = None):
        Part.__init__(self)
        MetaList.__init__(self,
                          list_type=ListType.PART,
                          data=[],
                          cycles=False,
                          parent=context)

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

        # Structural parent (position in the Part tree)
        self._part_parent: Optional[Composite] = parent

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
                    return value.get(time)
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
        self.data = list(self.data)
        self.data.append(part)
        import numpy as np
        self.data = np.array(self.data, dtype=object)
        self._update_duration(part)

    def get_child(self, idx: int) -> Part:
        return self.data[idx]

    def _update_duration(self, part: Part) -> None:
        self.duration += part.duration

    # ------------------------------------------------------------------
    # Render
    # ------------------------------------------------------------------

    def render(self, time: Ratio, context: Meta = None) -> Iterator[RenderResult]:
        ctx = self if context is None else context
        for child in self.data:
            yield child.render(time, ctx)

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

    def render(self, time: Ratio, context: Meta = None) -> List[RenderResult]:
        ctx = self if context is None else context
        return [child.render(time, ctx) for child in self.data]
