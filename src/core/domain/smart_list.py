# list_with_state.py
import numpy as np
from typing import Union, Type
from enum import Enum, auto

from core.domain.meta import Meta

class ListType(Enum):
    NUMBER = auto()
    STRING = auto()
    MATRIX = auto()
    GENERIC = auto()


class SmartList(Meta):
    """Lisp-like list powered by NumPy, with optional cyclic behavior."""

    def __init__(self, list_type: ListType, data, cycles: bool = False, parent=None):
        super().__init__(parent=parent)
        self.type = list_type
        self.data = data if isinstance(data, np.ndarray) else np.array(data)
        self.cycles = cycles
        self._cycle_pos = 0

    # ------------------------------------------------------------------
    # Cyclic helpers
    # ------------------------------------------------------------------

    def _wrap(self, idx: int) -> int:
        """Wrap an index cyclically."""
        if self.data.size == 0:
            raise IndexError("empty list")
        return idx % len(self.data)

    def rotate(self, n: int = 1) -> "SmartList":
        """Return a new SmartList rotated left by n (negative = right)."""
        return SmartList(self.type, np.roll(self.data, -n), cycles=self.cycles, parent=self._parent)

    def cycle_next(self):
        """Advance internal cursor and return that element (cyclic)."""
        item = self.data[self._wrap(self._cycle_pos)]
        self._cycle_pos += 1
        return item

    def cycle_reset(self):
        self._cycle_pos = 0

    # ------------------------------------------------------------------
    # Core operations
    # ------------------------------------------------------------------

    def inverse(self, axis: Union[int, tuple, None] = 0) -> "SmartList":
        """Reverse along axis (None = all axes)."""
        return SmartList(self.type, np.flip(self.data, axis=axis), cycles=self.cycles, parent=self._parent)

    def reverse(self) -> "SmartList":
        """Reverse the flat order of elements (alias for inverse on axis=0)."""
        return self.inverse(axis=0)

    def reshape(self, *shape) -> "SmartList":
        return SmartList(self.type, self.data.reshape(*shape), cycles=self.cycles, parent=self._parent)

    @property
    def T(self) -> "SmartList":
        return SmartList(self.type, self.data.T, cycles=self.cycles, parent=self._parent)

    @property
    def flat(self) -> "SmartList":
        return SmartList(self.type, self.data.flatten(), cycles=self.cycles, parent=self._parent)

    # ------------------------------------------------------------------
    # Math
    # ------------------------------------------------------------------

    def __neg__(self):      return SmartList(self.type, -self.data, cycles=self.cycles, parent=self._parent)
    def __pos__(self):      return SmartList(self.type, np.abs(self.data), cycles=self.cycles, parent=self._parent)
    def __abs__(self):      return SmartList(self.type, np.abs(self.data), cycles=self.cycles, parent=self._parent)
    def __pow__(self, exp): return SmartList(self.type, self.data ** exp, cycles=self.cycles, parent=self._parent)

    # ------------------------------------------------------------------
    # Comparisons (return filtered SmartList)
    # ------------------------------------------------------------------

    def __gt__(self, other): return SmartList(self.type, self.data[self.data > other])
    def __lt__(self, other): return SmartList(self.type, self.data[self.data < other])
    def __ge__(self, other): return SmartList(self.type, self.data[self.data >= other])
    def __le__(self, other): return SmartList(self.type, self.data[self.data <= other])
    def __eq__(self, other): return SmartList(self.type, self.data[self.data == other])

    # ------------------------------------------------------------------
    # Aggregation
    # ------------------------------------------------------------------

    @property
    def sum(self):  return self.data.sum()
    @property
    def mean(self): return self.data.mean()
    @property
    def min(self):  return self.data.min()
    @property
    def max(self):  return self.data.max()
    @property
    def std(self):  return self.data.std()

    # ------------------------------------------------------------------
    # Lisp classics
    # ------------------------------------------------------------------

    @property
    def car(self):
        return self.data[0] if self.data.size > 0 else None

    @property
    def cdr(self):
        return SmartList(self.type, self.data[1:], cycles=self.cycles, parent=self._parent) if self.data.size > 1 else None

    # ------------------------------------------------------------------
    # Sequence protocol
    # ------------------------------------------------------------------

    def __getitem__(self, arg):
        if isinstance(arg, int):
            if self.cycles:
                arg = self._wrap(arg)
            return self.data[arg]
        # Fall back to Meta's dict/parent chain lookup for non-integer keys
        return super().__getitem__(arg)

    def __len__(self):
        return len(self.data)

    def __iter__(self):
        """Finite iteration always — use cycle_next() for endless cycling."""
        return iter(self.data)

    # ------------------------------------------------------------------
    # Printing
    # ------------------------------------------------------------------

    def __repr__(self):
        return self.to_string(compact=True)

    def __str__(self):
        return self.to_string(compact=False)

    def to_string(self, compact: bool = False, indent: int = 0) -> str:
        indent_str = " " * indent
        cycle_tag = " :cyclic" if self.cycles else ""

        if self.data.size == 0:
            return f"({self.type.name}{cycle_tag})"

        if self.data.ndim == 1:
            elements = [
                item.to_string(compact, indent + 1)
                if isinstance(item, SmartList)
                else str(item)
                for item in self.data
            ]
            if compact:
                return f"({self.type.name}{cycle_tag} {' '.join(elements)})"
            line = f"({self.type.name}{cycle_tag} {' '.join(elements)})"
            if len(line) > 60:
                joined = f"\n{indent_str} ".join(elements)
                return f"({self.type.name}{cycle_tag}\n{indent_str} {joined}\n{indent_str})"
            return line

        # Multi-dimensional
        if compact:
            return f"({self.type.name}{cycle_tag} <{self.data.shape}> {self.data.tolist()})"

        lines = [f"({self.type.name}{cycle_tag}"]
        for row in self.data:
            sub = SmartList(self.type, row, cycles=self.cycles)
            lines.append(f"{indent_str} {sub.to_string(compact, indent + 1)}")
        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def prototype(self, indent: int = 0) -> str:
        indent_str = " " * indent
        if self.data.size == 0:
            return f"{indent_str}({self.type.name})"

        lines = [f"{indent_str}({self.type.name}"]
        if self.data.ndim == 1:
            for item in self.data:
                if isinstance(item, SmartList):
                    lines.append(item.prototype(indent + 1))
                elif isinstance(item, (int, float, np.number)):
                    lines.append(f"{indent_str} <number>")
                elif isinstance(item, str):
                    lines.append(f"{indent_str} <string>")
                else:
                    lines.append(f"{indent_str} <{type(item).__name__}>")
        else:
            for row in self.data:
                lines.append(SmartList(self.type, row).prototype(indent + 1))

        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def debug_print(self):
        print(f"=== Debug: {self.type.name} | cyclic={self.cycles} ===")
        print(f"  Shape: {self.data.shape}  |  ndim: {self.data.ndim}  |  dtype: {self.data.dtype}")
        print(f"  Compact:  {repr(self)}")
        print(f"  Pretty:\n{self.to_string(compact=False)}")
        print(f"  Prototype:\n{self.prototype()}")


# ----------------------------------------------------------------------
# Example usage
# ----------------------------------------------------------------------
if __name__ == "__main__":
    matrix = SmartList(ListType.MATRIX, [
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        [11, 12, 13, 14, 15, 16, 17, 18, 19, 20],
        [21, 22, 23, 24, 25, 26, 27, 28, 29, 30]
    ])

    numbers = SmartList(ListType.NUMBER, [1, 2, 3, 4, 5], cycles=True)
    strings = SmartList(ListType.STRING, ["hello", "world", "foo", "bar"])

    print("=" * 60)
    print("MATRIX — pretty print")
    print(matrix)

    print("\nMATRIX — transposed")
    print(matrix.T)

    print("\nMATRIX — reversed (axis=0, row order flipped)")
    print(matrix.reverse())

    print("\n" + "=" * 60)
    print("CYCLIC LIST — cycle_next() × 8 (only 5 elements)")
    for _ in range(8):
        print(numbers.cycle_next(), end=" ")
    print()

    print("\nCYCLIC — out-of-bounds index wraps around")
    print(numbers[7])   # 7 % 5 = 2  →  element at index 2

    print("\nCYCLIC — rotate left by 2")
    print(numbers.rotate(2))

    print("\n" + "=" * 60)
    print("DEBUG")
    numbers.debug_print()
