# meta.py
from abc import ABC
from typing import Optional

from core.domain.part import Part
from core.domain.point_envelope import Envelope


# ==============================================================================
# class Meta
# ==============================================================================

class Meta(Part):
    """Wraps a dict of Envelopes and supports parent-based key lookup."""
    _win_count = 0  # unique ID counter for multiple windows

    def __init__(self, parent: Optional["Meta"] = None, data: dict | None = None):
        if parent is not None and not isinstance(parent, Meta):
            raise TypeError(f"parent must be a Meta instance, not {type(parent).__name__}")
        self.parent = parent
        self._state: dict[str, Envelope] = dict(data) if data else {}

    # ── Core lookup ───────────────────────────────────────────────────────────

    def __getitem__(self, key) -> Envelope:
        if key in self._state:
            value = self._state[key]
            if isinstance(value, Envelope) and len(value) > 0:
                return value
            if self._parent is not None:
                return self._parent[key]
        raise KeyError(key)

    def __setitem__(self, key: str, value: Envelope):
        self._state[key] = value

    def __delitem__(self, key: str):
        del self._state[key]

    def __contains__(self, key: str) -> bool:
        return key in self._state

    def __iter__(self):
        return iter(self._state)

    def __len__(self) -> int:
        return len(self._state)

    def keys(self):
        return self._state.keys()

    def values(self):
        return self._state.values()

    def items(self):
        return self._state.items()

    def get(self, key: str, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def value(self, key: str, time: float, default=None):
        try:
            envelope = self[key]
            return envelope.get(time)
        except KeyError:
            return default

    # ── Parent management ─────────────────────────────────────────────────────

    @property
    def parent(self):
        return self._parent

    @parent.setter
    def parent(self, new_parent):
        if new_parent is not None and not isinstance(new_parent, Meta):
            raise TypeError(f"parent must be a Meta instance, not {type(new_parent).__name__}")
        self._parent = new_parent

    def depth(self) -> int:
        return 0 if self._parent is None else 1 + self._parent.depth()

    def all_keys(self) -> set:
        keys = set(self._state.keys())
        if self._parent is not None:
            keys |= self._parent.all_keys()
        return keys

    def resolved(self) -> dict:
        base = self._parent.resolved() if self._parent is not None else {}
        base.update(self._state)
        return base

    def __repr__(self):
        parent_info = f", parent={type(self._parent).__name__}" if self._parent is not None else ""
        return f"{type(self).__name__}({self._state!r}{parent_info})"


