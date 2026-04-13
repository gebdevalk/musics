from dataclasses import dataclass
from typing import Optional, Dict, Any

from core.domain.point_envelope import Envelope, IP
from core.elements.key_scale_keyscale import KeyScale, KEYS, SCALES
from core.elements.meter import M44
from core.elements.tempo import Tempo
from midi.constants import Volume
from tools.ratio import Ratio


@dataclass
class Context:
    parent: Optional["Context"] = None
    _state: Dict[str, Envelope] = None

    def __post_init__(self):
        if self._state is None:
            self._state = {}

    def __getitem__(self, key: str) -> Envelope:
        if key in self._state:
            return self._state[key]
        if self.parent is not None:
            return self.parent[key]
        raise KeyError(key)

    def value(self, key: str, time: float, default=None):
        try:
            return self[key].get(time)
        except KeyError:
            return default

    def set(self, key: str, env: Envelope):
        self._state[key] = env

    def resolved(self) -> Dict[str, Envelope]:
        base = self.parent.resolved() if self.parent else {}
        base.update(self._state)
        return base

    def __repr__(self):
        return f"Context(keys={list(self._state.keys())}, parent={self.parent is not None})"


# class Root(Context):
#     def __init__(self, values: Dict[str, Any] = None):
#         self.parent = None
#         self._state = {}
#
#         if values:
#             for key, val in values.items():
#                 env = Envelope()
#                 env.add(0.0, val, IP.FIXED)
#                 self._state[key] = env
#
#     def __repr__(self):
#         return f"Root(keys={list(self._state.keys())})"

class Root(Context):
    """
    Root of the context chain.
    Holds global musical defaults, wrapped in constant envelopes.
    """
    def __init__(self, values: Dict[str, Any] = None):
        self.parent = None
        self._state = {}

        if values:
            for key, val in values.items():
                env = Envelope()
                env.add(0.0, val, IP.FIXED)
                self._state[key] = env

    def __repr__(self):
        return f"Root(keys={list(self._state.keys())})"


ROOT = Root(values={
    "tempo": Tempo(Ratio(1, 4), 92),
    "keyScale": KeyScale(KEYS["C"], SCALES["major"]),
    "measure": M44,
    "volume": Volume.DYNAMICS["MF"],
    "articulation": 0.9,
    "transposition": 0,
    "timbre": 0,
    "panning": 0.0,
})
