# part.py
from abc import ABC
from dataclasses import dataclass, replace
from typing import Optional

from tools.ratio import Ratio
from core.domain.context import Context


@dataclass
class Part(ABC):
    """
    Pure structural node.
    Holds a reference to a Context (semantic environment).
    """
    context: Optional[Context] = None
    duration: Ratio = Ratio(0, 1)

    def clone(self) -> "Part":
        return replace(self)

    def nav(self, *indices: int) -> Optional['Part']:
        """
        Navigate using variable number of indices:
        nav() -> self
        nav(0) -> first child
        nav(1,0) -> first child of second child
        """
        current = self
        for idx in indices:
            if current is None:
                return None

            if hasattr(current, 'get_child') and callable(current.get_child):
                try:
                    current = current.get_child(idx)
                except (IndexError, TypeError):
                    return None
            else:
                return None
        return current
