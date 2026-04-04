# part.py

from abc import ABC
from dataclasses import dataclass, replace
from typing import Optional

from tools import ratio
from tools.ratio import Ratio


# =========================
# Core Part hierarchy
# =========================

@dataclass
class Part(ABC):
    """ Parts are context-free. Composite owns all state. """
    duration: Ratio = ratio.ZERO

    def __post_init__(self):
        pass

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

            # Try to get child - will fail for leaf nodes
            try:
                # Check if it's a composite by looking for get_child method
                if hasattr(current, 'get_child') and callable(current.get_child):
                    current = current.get_child(idx)
                else:
                    return None
            except (AttributeError, IndexError, TypeError):
                return None
        return current
