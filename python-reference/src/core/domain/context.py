# core/domain/context.py

from __future__ import annotations
from typing import NamedTuple, Dict, Optional, Any

from common.tools.smart_dict import SmartDict
from core.domain.envelope import Envelope, IP


class Context(NamedTuple):
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

       Lookup semantics:
           - A Context stores only *local overrides*.
           - If a key is not present locally, lookup continues in the parent.
           - Values are always Envelopes (never raw values).
       """

    parent: Optional["Context"]
    envelopes: Dict[str, Envelope] = SmartDict({})

    # ------------------------------------------------------------
    # Key lookup
    # ------------------------------------------------------------

    def __str__(self) -> str:
        """
        Pretty-print the Context and its recursive parent chain.
        Shows each envelope key with its points (time, value, ip).
        """
        def _render(context: "Context", depth: int) -> list[str]:
            indent = "  " * depth
            lines = []
            if context.parent is not None:
                lines.append(f"{indent}parent:")
                lines.extend(_render(context.parent, depth + 1))
            if context.envelopes:
                lines.append(f"{indent}envelopes:")
                for key, env in sorted(context.envelopes.items()):
                    points_str = ", ".join(
                        f"({p.time:.2f}, {p.value!r}, {p.ip.value})"
                        for p in env.points
                    )
                    lines.append(f"{indent}  {key}: [{points_str}]")
            if not lines:
                lines.append(f"{indent}(empty)")
            return lines

        return "\n".join(_render(self, 0))

    def _find_key(self, key: str) -> Optional[Envelope]:
        if key in self.envelopes:
            return self.envelopes[key]
        if self.parent is None:
            return None
        return self.parent._find_key(key)

    # ------------------------------------------------------------
    # Value sampling
    # ------------------------------------------------------------
    def value(self, key: str, time: float):
        env = self._find_key(key)
        return None if env is None else env.get(time)

    # ------------------------------------------------------------
    # Append a key/value/IP
    # ------------------------------------------------------------

    def append(self, key: str, time: float, value, ip: IP=IP.FIXED) -> None:
        """
        Add a point to the envelope for `key`.

        Rules:
        - If key exists locally: append to that envelope.
        - If key exists only in a parent: create a new envelope locally.
        - If key exists nowhere: create a new envelope locally.
        - Always append at time = envelope.duration.
        - Return the same Context object (Context is immutable, dict is mutable).
        """
        if key in self.envelopes:
            env = self.envelopes[key]
            env.append(time, value, ip)
            return

        # Case 2: no local envelope → create new
        env = Envelope()
        env.append(time, value, ip)
        self.envelopes[key] = env

    # @classmethod
    # def create(cls, data: Dict[str, Any]) -> Context:
    #     """
    #     Create a Context from dict.
    #     Example:
    #         {"parent": None, "state": {}}
    #     """
    #     parent = data.get("parent")
    #     context = cls(parent=parent)
    #     for key, envelope_data in data.get("state", {}).items():
    #         context._state[key] = Envelope.from_dict(envelope_data)
    #     return context

    @classmethod
    def root(cls, data: Dict[str, Any]) -> Context:
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
        context = cls(parent=None)
        for key, value in data.items():
            context.append(key, 0.0, value, IP.FIXED)
        return context

# ------------------------------------------------------------
# Create new  Context
# ------------------------------------------------------------

# def create_context(parent: Context) -> Context:
#     """
#     Create a new Context with the given parent.
#     The envelopes dict starts empty.
#
#     Raises:
#         ValueError if parent is None.
#     """
#     if parent is None:
#         raise ValueError("Context.create() requires a non-None parent")
#     return Context(parent=parent, envelopes={})


# ------------------------------------------------------------
# main: quick smoke-test
# ------------------------------------------------------------

def main():
    """Create a Context from a dict and print it."""
    data = {
        "tempo": 120,
        "volume": 0.8,
        "timbre": 42,
    }
    ctx = Context.root(data)
    print(ctx)

if __name__ == '__main__':
    main()