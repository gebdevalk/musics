# smart_list.py

import numpy as np
from typing import Union, Type
from core.domain.leafs import Part


class SmartList:
    """
    Pure NumPy-backed list with music-useful array operations.
    Starts as a plain Python list; call finalize() to convert to NumPy
    and unlock array operations.
    Type is inferred from content on finalize, or can be set explicitly.
    """

    def __init__(self, data=None, type_hint: Type = None, cycles: bool = False):
        self.type = type_hint       # None = infer on finalize
        self.cycles = cycles
        self._cycle_pos = 0
        self._finalized = False

        if data is None:
            self.data = []
        elif isinstance(data, np.ndarray):
            self.data = data
            self._finalized = True
            if self.type is None:
                self.type = self._infer_type()
        elif isinstance(data, list):
            self.data = data
        else:
            self.data = list(data)

    # ------------------------------------------------------------------
    # Finalization
    # ------------------------------------------------------------------

    def finalize(self) -> "SmartList":
        if not self._finalized:
            if self.type is None:
                self.type = self._infer_type()
            self.data = np.array(self.data, dtype=object)
            self._finalized = True
        return self

    def _infer_type(self) -> Type:
        sample = next((x for x in self.data if x is not None), None)
        if sample is None:
            return object
        t = type(sample)
        if issubclass(t, Part):       return Part
        if issubclass(t, SmartList):  return SmartList
        if issubclass(t, str):        return str
        if issubclass(t, (int, np.integer)):   return int
        if issubclass(t, (float, np.floating)): return float
        return t

    def _require_finalized(self):
        if not self._finalized:
            raise RuntimeError("Call finalize() before using array operations.")

    def _require_mutable(self):
        if self._finalized:
            raise RuntimeError("SmartList is finalized. Cannot mutate after finalize().")

    # ------------------------------------------------------------------
    # Mutable phase (pre-finalize)
    # ------------------------------------------------------------------

    def append(self, item) -> None:
        self._require_mutable()
        self.data.append(item)

    def extend(self, items) -> None:
        self._require_mutable()
        self.data.extend(items)

    # ------------------------------------------------------------------
    # Cyclic helpers
    # ------------------------------------------------------------------

    def _wrap(self, idx: int) -> int:
        if len(self.data) == 0:
            raise IndexError("empty list")
        return idx % len(self.data)

    def cycle_next(self):
        item = self.data[self._wrap(self._cycle_pos)]
        self._cycle_pos += 1
        return item

    def cycle_reset(self):
        self._cycle_pos = 0

    # ------------------------------------------------------------------
    # Array operations (require finalized)
    # ------------------------------------------------------------------

    def rotate(self, n: int = 1) -> "SmartList":
        """Rotate left by n steps. Useful for mode rotation."""
        self._require_finalized()
        return self._make(np.roll(self.data, -n))

    def reverse(self) -> "SmartList":
        """Reverse element order. Useful for retrograde."""
        self._require_finalized()
        return self._make(np.flip(self.data, axis=0))

    def invert(self, axis: Union[int, tuple, None] = 0) -> "SmartList":
        """Flip along axis. Useful for melodic inversion on matrices."""
        self._require_finalized()
        return self._make(np.flip(self.data, axis=axis))

    def transpose(self, semitones: int) -> "SmartList":
        """Shift all values by semitones. Only meaningful for numeric types."""
        self._require_finalized()
        if self.type not in (float, int):
            raise TypeError(f"transpose requires numeric type, got {self.type.__name__}")
        return self._make(self.data + semitones)

    def reshape(self, *shape) -> "SmartList":
        self._require_finalized()
        return self._make(self.data.reshape(*shape))

    @property
    def T(self) -> "SmartList":
        """Matrix transpose. Useful for pitch/rhythm grids."""
        self._require_finalized()
        return self._make(self.data.T)

    @property
    def flat(self) -> "SmartList":
        self._require_finalized()
        return self._make(self.data.flatten())

    def retrograde_inversion(self, axis: Union[int, tuple, None] = 0) -> "SmartList":
        """Reverse then invert — common twelve-tone operation."""
        self._require_finalized()
        return self.reverse().invert(axis)

    # ------------------------------------------------------------------
    # Aggregation (numeric only, require finalized)
    # ------------------------------------------------------------------

    def _require_numeric(self):
        if self.type not in (float, int):
            raise TypeError(f"Aggregation requires numeric type, got {self.type.__name__}")

    @property
    def sum(self):
        self._require_finalized(); self._require_numeric()
        return self.data.sum()

    @property
    def mean(self):
        self._require_finalized(); self._require_numeric()
        return self.data.mean()

    @property
    def min(self):
        self._require_finalized(); self._require_numeric()
        return self.data.min()

    @property
    def max(self):
        self._require_finalized(); self._require_numeric()
        return self.data.max()

    @property
    def std(self):
        self._require_finalized(); self._require_numeric()
        return self.data.std()

    # ------------------------------------------------------------------
    # Lisp classics (both phases)
    # ------------------------------------------------------------------

    @property
    def car(self):
        return self.data[0] if len(self.data) > 0 else None

    @property
    def cdr(self) -> "SmartList":
        if len(self.data) <= 1:
            return None
        tail = self.data[1:]
        return self._make(tail) if self._finalized else SmartList(list(tail), self.type, self.cycles)

    # ------------------------------------------------------------------
    # Sequence protocol (both phases)
    # ------------------------------------------------------------------

    def __getitem__(self, idx: int):
        if not isinstance(idx, int):
            raise TypeError(f"SmartList indices must be integers, not {type(idx).__name__}")
        if self.cycles:
            idx = self._wrap(idx)
        return self.data[idx]

    def __len__(self):
        return len(self.data)

    def __iter__(self):
        return iter(self.data)

    # ------------------------------------------------------------------
    # Internal factory
    # ------------------------------------------------------------------

    def _make(self, data: np.ndarray) -> "SmartList":
        sl = SmartList(data, type_hint=self.type, cycles=self.cycles)
        sl._finalized = True
        return sl

    # ------------------------------------------------------------------
    # Printing
    # ------------------------------------------------------------------

    def __repr__(self):
        return self.to_string(compact=True)

    def __str__(self):
        return self.to_string(compact=False)

    def _type_tag(self) -> str:
        name = self.type.__name__ if self.type is not None else "?"
        cycle = " :cyclic" if self.cycles else ""
        mut = "" if self._finalized else " :mutable"
        return f"{name}{cycle}{mut}"

    def to_string(self, compact: bool = False, indent: int = 0) -> str:
        indent_str = " " * indent
        tag = self._type_tag()

        if len(self.data) == 0:
            return f"({tag})"

        is_matrix = isinstance(self.data, np.ndarray) and self.data.ndim > 1

        if not is_matrix:
            elements = [
                item.to_string(compact, indent + 1)
                if isinstance(item, SmartList)
                else str(item)
                for item in self.data
            ]
            if compact:
                return f"({tag} {' '.join(elements)})"
            line = f"({tag} {' '.join(elements)})"
            if len(line) > 60:
                joined = f"\n{indent_str} ".join(elements)
                return f"({tag}\n{indent_str} {joined}\n{indent_str})"
            return line

        if compact:
            return f"({tag} <{self.data.shape}> {self.data.tolist()})"

        lines = [f"({tag}"]
        for row in self.data:
            sub = SmartList(row, type_hint=self.type, cycles=self.cycles)
            lines.append(f"{indent_str} {sub.to_string(compact, indent + 1)}")
        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def prototype(self, indent: int = 0) -> str:
        indent_str = " " * indent
        type_name = self.type.__name__ if self.type else "?"

        if len(self.data) == 0:
            return f"{indent_str}({type_name})"

        lines = [f"{indent_str}({type_name}"]
        is_matrix = isinstance(self.data, np.ndarray) and self.data.ndim > 1

        if not is_matrix:
            for item in self.data:
                if isinstance(item, SmartList):
                    lines.append(item.prototype(indent + 1))
                else:
                    lines.append(f"{indent_str} <{type(item).__name__}>")
        else:
            for row in self.data:
                lines.append(SmartList(row, type_hint=self.type).prototype(indent + 1))

        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def debug_print(self):
        state = "finalized" if self._finalized else "mutable"
        type_name = self.type.__name__ if self.type else "?"
        print(f"=== SmartList<{type_name}> | cyclic={self.cycles} | {state} ===")
        if self._finalized:
            print(f"  Shape: {self.data.shape}  |  ndim: {self.data.ndim}  |  dtype: {self.data.dtype}")
        else:
            print(f"  Length: {len(self.data)}")
        print(f"  Compact : {repr(self)}")
        print(f"  Pretty  :\n{self.to_string(compact=False)}")
        print(f"  Prototype:\n{self.prototype()}")
