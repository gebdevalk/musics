# list_with_state.py
from numbers import Number
from tokenize import String

import numpy as np
from typing import Any, Union, Optional, List, Type
from dataclasses import dataclass

from cairo import Matrix


@dataclass
class LispList:
    """Lisp-like list powered by NumPy"""
    type: Type  # Type metadata
    data: np.ndarray  # NumPy array for performance

    def __post_init__(self):
        """Ensure domain is a NumPy array"""
        if not isinstance(self.data, np.ndarray):
            self.data = np.array(self.data)

    # Core operations (1-liners with NumPy)
    def reverse(self, axis: Union[int, tuple, None] = 0):
        """Reverse along axis (None = all axes)"""
        return LispList(self.type, np.flip(self.data, axis=axis))

    def reshape(self, *shape):
        """Reshape the array"""
        return LispList(self.type, self.data.reshape(*shape))

    @property
    def T(self):
        """Transpose"""
        return LispList(self.type, self.data.T)

    @property
    def flat(self):
        """Flatten the array"""
        return LispList(self.type, self.data.flatten())

    # Mathematical operations (vectorized)
    def __neg__(self):
        return LispList(self.type, -self.data)

    def __pos__(self):
        return LispList(self.type, np.abs(self.data))

    def __abs__(self):
        return LispList(self.type, np.abs(self.data))

    def __pow__(self, exp):
        return LispList(self.type, self.data ** exp)

    # Comparison operators (return masks or filtered lists)
    def __gt__(self, other):
        return LispList(self.type, self.data[self.data > other])

    def __lt__(self, other):
        return LispList(self.type, self.data[self.data < other])

    def __ge__(self, other):
        return LispList(self.type, self.data[self.data >= other])

    def __le__(self, other):
        return LispList(self.type, self.data[self.data <= other])

    def __eq__(self, other):
        return LispList(self.type, self.data[self.data == other])

    # Aggregation operations
    @property
    def sum(self):
        return self.data.sum()

    @property
    def mean(self):
        return self.data.mean()

    @property
    def min(self):
        return self.data.min()

    @property
    def max(self):
        return self.data.max()

    @property
    def std(self):
        return self.data.std()

    # Lisp classic operations
    @property
    def car(self):
        """First element"""
        return self.data[0] if self.data.size > 0 else None

    @property
    def cdr(self):
        """Rest of the list"""
        return LispList(self.type, self.data[1:]) if self.data.size > 1 else None

    # Sequence protocol
    def __getitem__(self, idx):
        return self.data[idx]

    def __len__(self):
        return len(self.data)

    def __iter__(self):
        return iter(self.data)

    # ========== NEW PRINTING METHODS ==========

    def __repr__(self):
        """Compact representation (one line)"""
        return self.to_string(compact=True)

    def __str__(self):
        """User-friendly string representation (pretty printed)"""
        return self.to_string(compact=False)

    def to_string(self, compact: bool = False, indent: int = 0) -> str:
        """
        Convert to string with optional pretty printing.

        Args:
            compact: If True, one-line representation. If False, pretty printed with indentation.
            indent: Current indentation level (for recursive calls)
        """
        indent_str = " " * indent

        # Handle empty list
        if self.data.size == 0:
            return f"({self.type.name})"

        # Handle 1D arrays (simple lists)
        if self.data.ndim == 1:
            elements = []
            for item in self.data:
                if isinstance(item, LispList):
                    elements.append(item.to_string(compact, indent + 1))
                else:
                    elements.append(str(item))

            if compact:
                return f"({self.type.name} {' '.join(elements)})"
            else:
                # Pretty print with wrapping if too long
                line = f"({self.type.name} {' '.join(elements)})"
                if len(line) > 60:  # Wrap long lines
                    return (f"({self.type.name}\n" +
                            " " * (indent + 1) +
                            f"\n{indent_str} ".join(elements) +
                            f"\n{indent_str})")
                return line

        # Handle multi-dimensional arrays
        else:
            if compact:
                # Compact: just show shape
                return f"({self.type.name} <{self.data.shape}> {self.data.tolist()})"
            else:
                # Pretty print with indentation
                lines = [f"({self.type.name}"]

                # Format each row/dimension
                if self.data.ndim == 2:
                    for row in self.data:
                        row_list = LispList(self.type, row)
                        lines.append(f"{indent_str} {row_list.to_string(compact, indent + 1)}")
                else:
                    # Higher dimensions
                    for i, item in enumerate(self.data):
                        if isinstance(item, np.ndarray):
                            sub_list = LispList(self.type, item)
                            lines.append(f"{indent_str} {sub_list.to_string(compact, indent + 1)}")
                        else:
                            lines.append(f"{indent_str} {item}")

                lines.append(f"{indent_str})")
                return "\n".join(lines)

    def prototype(self, indent: int = 0) -> str:
        """
        Generate an indented prototype showing the structure.
        Shows types and nesting levels without values.
        """
        indent_str = " " * indent
        lines = [f"{indent_str}({self.type.name}"]

        if self.data.size == 0:
            return f"{indent_str}({self.type.name})"

        if self.data.ndim == 1:
            # Show type pattern for 1D
            for item in self.data:
                if isinstance(item, LispList):
                    lines.append(item.prototype(indent + 1))
                else:
                    # Show type placeholder
                    if isinstance(item, (int, float)):
                        lines.append(f"{indent_str} <number>")
                    elif isinstance(item, str):
                        lines.append(f"{indent_str} <string>")
                    else:
                        lines.append(f"{indent_str} <{type(item).__name__}>")
        else:
            # Show structure for multi-dimensional
            for i, item in enumerate(self.data):
                if isinstance(item, np.ndarray):
                    sub_list = LispList(self.type, item)
                    lines.append(sub_list.prototype(indent + 1))
                else:
                    lines.append(f"{indent_str} <dimension_{i}>")

        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def debug_print(self):
        """Print detailed debug information about the list"""
        print(f"=== Debug Info for {self.type.name} List ===")
        print(f"Type: {self.type.name}")
        print(f"Shape: {self.data.shape}")
        print(f"Dimensions: {self.data.ndim}")
        print(f"Size: {self.data.size}")
        print(f"Data type: {self.data.dtype}")
        print(f"Compact repr: {self}")
        print(f"Pretty printed:\n{self.to_string(compact=False)}")
        print(f"Prototype:\n{self.prototype()}")


# Example usage
if __name__ == "__main__":
    # Create test domain
    matrix = Matrix([
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        [11, 12, 13, 14, 15, 16, 17, 18, 19, 20],
        [21, 22, 23, 24, 25, 26, 27, 28, 29, 30]
    ])

    nested = List([
        Number([1, 2, 3, 4, 5]),
        String(["hello", "world", "this", "is", "a", "test"]),
        Number([6, 7, 8, 9, 10])
    ])

    # Test different print formats
    print("=" * 60)
    print("COMPACT REPR (__repr__)")
    print("=" * 60)
    print(repr(matrix))
    print()
    print(repr(nested))

    print("\n" + "=" * 60)
    print("PRETTY PRINT (__str__)")
    print("=" * 60)
    print(matrix)
    print()
    print(nested)

    print("\n" + "=" * 60)
    print("PROTOTYPE (structure only)")
    print("=" * 60)
    print(matrix.prototype())
    print()
    print(nested.prototype())

    print("\n" + "=" * 60)
    print("DEBUG PRINT")
    print("=" * 60)
    matrix.debug_print()
