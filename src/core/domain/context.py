# context.py

from __future__ import annotations
from typing import Dict, Any, Optional

from core.domain.point_envelope import Envelope, IP


class Context:
    """
    A hierarchical container for expressive musical metadata.

    Context stores *envelopes* for expressive parameters such as:
        - tempo
        - keyScale
        - measure
        - volume
        - articulation
        - transposition
        - timbre
        - panning
        - any user-defined expressive key

    Contexts form a chain:
        parent → child → leaf.context

    Lookup semantics:
        - A Context stores only *local overrides*.
        - If a key is not present locally, lookup continues in the parent.
        - Values are always Envelopes (never raw values).

    Context does NOT:
        - resolve envelopes
        - sample values
        - compute time-dependent results

    The performer is responsible for sampling:
        env = context.get("volume")
        value = env.get(t)
    """
    __slots__ = ("parent", "_state", "_hooks")

    def __init__(self, parent: Optional['Context'] = None):
        # Parent context (None only for the root context)
        self.parent: Optional[Context] = parent
        # Local overrides: key → Envelope
        self._state: Dict[str, Envelope] = {}
        self._hooks: dict[str, list] = {}

    # ------------------------------------------------------------
    # Envelope access
    # ------------------------------------------------------------

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

    def get(self, key: str) -> Optional[Envelope]:
        """
        Retrieve the envelope for a given key.
        Lookup rules:
            - If the key exists locally, return the local envelope.
            - Otherwise, delegate to the parent.
            - If no parent has the key, return None.
        """
        if key in self._state:
            return self._state[key]
        if self.parent is not None:
            return self.parent.get(key)
        return None

    def set(self, key: str, envelope: Envelope) -> None:
        """
        Set an envelope override for this context.
        This replaces any existing envelope for the key.
        """
        self._state[key] = envelope

    def append_hook(self, event: str, fn):
        """
        Register a callback for a performer event.
        Supported events:
            - "before_container"
            - "after_container"
            - "after_beat"
        """
        if event not in self._hooks:
            self._hooks[event] = []
        self._hooks[event].append(fn)

    # ------------------------------------------------------------
    # Envelope creation helpers
    # ------------------------------------------------------------

    def append_point(self, key: str, time: float, value: Any, ip: IP = IP.FIXED) -> None:
        """
        Add a point to the envelope for the given key.
        If the envelope does not exist yet, it is created lazily.
        """
        if key not in self._state:
            self._state[key] = Envelope()
        self._state[key].append(time, value, ip)

    # ------------------------------------------------------------
    # Introspection
    # ------------------------------------------------------------

    def has_local(self, key: str) -> bool:
        """Return True if this context defines a local override for the key."""
        return key in self._state

    def keys(self):
        """Return the keys defined locally in this context."""
        return self._state.keys()

    def __repr__(self) -> str:
        local_keys = ", ".join(self._state.keys())
        return f"Context(local=[{local_keys}])"


# ============================================================
# Context Factory
# ============================================================

def wrap_in_envelopes(values: Dict[str, Any]) -> Context:
    """
    Create a root Context instance containing the complete set of
    expressive parameters, each wrapped in a constant Envelope.
    Root is not a subclass — it is simply a Context with no parent
    and a fully populated _state.
    Example:
        root = make_root({
            "tempo": Tempo(...),
            "volume": 0.8,
            "timbre": 0,
            "panning": 0.0,
        })
    """
    root = Context(parent=None)

    for key, value in values.items():
        env = Envelope()
        env.append(0.0, value, IP.FIXED)
        root.set(key, env)

    return root
