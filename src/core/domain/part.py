# part.py

from __future__ import annotations

from abc import ABC
from dataclasses import replace
from typing import Optional

from nanoid import generate

from core.domain.context import Context
from tools.ratio import Ratio


class Identifiable:
    ___slots__ = ("id", "__weakref__")   # ← important addition

    def __init__(self):
        # compact, collision-safe, musician-friendly ID
        self.id = generate(size=8)


class Part(ABC):
    """
    Base class for all musical structural nodes.

    A Part:
        • may have a Context (Containers do)
        • may have children (Containers do)
        • may be a leaf (Leafs do not have their own Context)
        • participates in the context inheritance chain

    Context rules:
        - Part itself does NOT create a context.
        - Containers create their own Context.
        - Leafs inherit the context of their parent container.
        - Score attaches the root context as parent of the top-level Part.
    """

    __slots__ = ("context", "duration")

    def __init__(self, *, context: Context | None = None):
        self.context: Context | None = context
        self.duration: Ratio = Ratio(0, 1)

    # ------------------------------------------------------------
    # Cloning
    # ------------------------------------------------------------

    def clone(self) -> Part:
        """
        Return a shallow clone of this Part.

        Context is NOT deep-copied — the caller is responsible for
        reattaching context parents after cloning.
        """
        return replace(self)

    # ------------------------------------------------------------
    # Navigation
    # ------------------------------------------------------------

    def nav(self, *indices: int) -> Optional[Part]:
        """
        Navigate into nested Parts using a sequence of indices.

        nav()        → self
        nav(0)       → first child
        nav(1, 0)    → first child of second child

        Returns None if navigation fails.
        """
        current: Optional[Part] = self

        for idx in indices:
            if current is None:
                return None

            if hasattr(current, "get_child") and callable(current.get_child):
                try:
                    current = current.get_child(idx)
                except (IndexError, TypeError):
                    return None
            else:
                return None

        return current

    def __repr__(self):
        return f"{self.__class__.__name__}(duration={self.duration}, context={self.context})"
