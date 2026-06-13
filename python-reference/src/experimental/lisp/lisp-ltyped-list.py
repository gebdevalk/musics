import numpy as np
from typedpy import Structure, String, Array, Field
from typing import Optional, Any, Dict, Union
from collections import ChainMap


# ============================================
# TYPE SYSTEM - Fixed for typedpy compatibility
# ============================================

class Type(Structure):
    """Type system with properties and upward chain"""
    name: String(pattern='^[a-z][a-zA-Z0-9_]*$')  # lowercase
    is_type: Optional[str] = None  # Store type name as string
    has: Optional[Array[str]] = None  # Store type names as strings

    # Remove default value entirely - make it required
    properties: Dict[str, Any]

    class _immutable:
        pass


# Type registry
_type_registry = {}


def define_type(name: str, is_type_name: Optional[str] = None, properties: Dict = None) -> 'Type':
    """Define and register a new type."""
    if name in _type_registry:
        return _type_registry[name]

    # Always provide a properties dict (empty if None)
    props = properties if properties is not None else {}
    type_obj = Type(name=name, is_type=is_type_name, properties=props)
    _type_registry[name] = type_obj
    return type_obj


def resolve_type(type_name: Optional[str]) -> Optional['Type']:
    """Resolve a type name to an actual Type object."""
    if type_name is None:
        return None
    return _type_registry.get(type_name)


# Define core type hierarchy (all lowercase)
object = define_type("object", properties={"mutable": True})
atom = define_type("atom", is_type_name="object", properties={"atomic": True})
number = define_type("number", is_type_name="atom", properties={"numeric": True})
integer = define_type("integer", is_type_name="number", properties={"integral": True})
string = define_type("string", is_type_name="atom", properties={"encoding": "utf-8"})
list_type = define_type("list", is_type_name="object", properties={"sequential": True})
vector = define_type("vector", is_type_name="list", properties={"dimensional": True})


# ============================================
# TYPE METHODS
# ============================================

def is_subtype_of(type_obj: Type, other: Union[str, Type]) -> bool:
    """Check if this type is a subtype of another type."""
    if isinstance(other, str):
        target_name = other
        current_name = type_obj.name
        while current_name:
            if current_name == target_name:
                return True
            current = resolve_type(current_name)
            current_name = current.is_type if current else None
        return False
    else:
        current_name = type_obj.name
        while current_name:
            current = resolve_type(current_name)
            if current == other:
                return True
            current_name = current.is_type if current else None
        return False


def get_property(type_obj: Type, name: str, default=None):
    """Get property by walking up the inheritance chain."""
    prop_maps = []
    current_name = type_obj.name
    while current_name:
        current = resolve_type(current_name)
        if current and hasattr(current, 'properties') and current.properties:
            prop_maps.append(current.properties)
        current_name = current.is_type if current else None
    # Chain in reverse order so child properties override parent properties
    chain = ChainMap(*reversed(prop_maps)) if prop_maps else {}
    return chain.get(name, default)


def get_ancestors(type_obj: Type) -> list:
    """Get all ancestor types in order."""
    ancestors = []
    current_name = type_obj.is_type
    while current_name:
        current = resolve_type(current_name)
        if current:
            ancestors.append(current)
            current_name = current.is_type
        else:
            break
    return ancestors


# Add methods to Type instances
Type.is_subtype_of = lambda self, other: is_subtype_of(self, other)
Type.get_property = lambda self, name, default=None: get_property(self, name, default)
Type.get_ancestors = lambda self: get_ancestors(self)


# ============================================
# LISP LIST IMPLEMENTATION
# ============================================

class lisp:
    """
    Lisp-style list factory. Creates lists where the first element is the type.
    Usage: lisp.number(1, 2, 3) -> (number 1 2 3)
    """

    class _type_factory:
        def __init__(self, type_obj: Type):
            self.type = type_obj

        def __call__(self, *args):
            """Create a new list: lisp.number(1, 2, 3)"""
            return LispList(self.type, args)

        def __getattr__(self, name):
            """Access type properties: lisp.number.numeric"""
            return self.type.get_property(name)

        def __repr__(self):
            return f"<lisp.{self.type.name}>"

    def __getattr__(self, name):
        """Get factory for type: lisp.number, lisp.string, etc."""
        if name in _type_registry:
            return self._type_factory(_type_registry[name])
        raise AttributeError(f"Unknown type: {name}")


# Create singleton instance
lisp = lisp()


class LispList:
    """Lisp-style list with type as first element."""

    def __init__(self, type_obj: Type, data):
        self.type = type_obj

        # Store as regular Python list
        if data is not None:
            self.data = list(data)
        else:
            self.data = []

    # ========== TYPE SYSTEM INTEGRATION ==========

    def isinstance(self, type_name_or_obj: Union[str, Type]) -> bool:
        """Check if this list's type is an instance of the given type."""
        if isinstance(type_name_or_obj, str):
            return is_subtype_of(self.type, type_name_or_obj)
        else:
            return is_subtype_of(self.type, type_name_or_obj)

    @property
    def type_name(self):
        return self.type.name

    def get_property(self, name: str, default=None):
        """Get property from type hierarchy."""
        return get_property(self.type, name, default)

    # ========== LISP CLASSIC OPERATIONS ==========

    @property
    def car(self):
        """First element (after type)."""
        return self.data[0] if len(self.data) > 0 else None

    @property
    def cdr(self):
        """Rest of the list (after first element)."""
        if len(self.data) <= 1:
            return None
        return LispList(self.type, self.data[1:])

    @property
    def caar(self):
        """First element of first element."""
        if len(self.data) > 0 and hasattr(self.data[0], '__getitem__'):
            try:
                return self.data[0][0]
            except (IndexError, TypeError):
                return None
        return None

    @property
    def cadr(self):
        """Second element."""
        return self.data[1] if len(self.data) > 1 else None

    @property
    def cddr(self):
        """Rest after second element."""
        if len(self.data) <= 2:
            return None
        return LispList(self.type, self.data[2:])

    # ========== NUMPY POWER (only when data is numeric) ==========

    def reverse(self, axis: Union[int, tuple, None] = 0):
        """Reverse along axis."""
        try:
            # Try numpy if data is numeric
            import numpy as np
            return LispList(self.type, np.flip(np.array(self.data), axis=axis).tolist())
        except:
            # Fallback to Python reverse
            new_data = self.data.copy()
            if axis == 0:
                new_data.reverse()
            return LispList(self.type, new_data)

    def reshape(self, *shape):
        """Reshape the array."""
        try:
            import numpy as np
            return LispList(self.type, np.array(self.data).reshape(*shape).tolist())
        except:
            return self

    @property
    def T(self):
        """Transpose."""
        try:
            import numpy as np
            return LispList(self.type, np.array(self.data).T.tolist())
        except:
            return self

    @property
    def flat(self):
        """Flatten the array."""
        try:
            import numpy as np
            return LispList(self.type, np.array(self.data).flatten().tolist())
        except:
            # Simple flatten for nested lists
            result = []
            for item in self.data:
                if isinstance(item, list):
                    result.extend(item)
                else:
                    result.append(item)
            return LispList(self.type, result)

    # ========== MATHEMATICAL OPERATIONS ==========

    def __neg__(self):
        try:
            return LispList(self.type, [-x for x in self.data])
        except:
            return self

    def __pos__(self):
        try:
            return LispList(self.type, [abs(x) for x in self.data])
        except:
            return self

    def __abs__(self):
        try:
            return LispList(self.type, [abs(x) for x in self.data])
        except:
            return self

    def __pow__(self, exp):
        try:
            return LispList(self.type, [x ** exp for x in self.data])
        except:
            return self

    @property
    def sum(self):
        try:
            return sum(self.data)
        except:
            return None

    @property
    def mean(self):
        try:
            return sum(self.data) / len(self.data) if self.data else None
        except:
            return None

    @property
    def min(self):
        try:
            return min(self.data) if self.data else None
        except:
            return None

    @property
    def max(self):
        try:
            return max(self.data) if self.data else None
        except:
            return None

    # ========== FILTERING ==========

    def __gt__(self, other):
        try:
            return LispList(self.type, [x for x in self.data if x > other])
        except:
            return LispList(self.type, [])

    def __lt__(self, other):
        try:
            return LispList(self.type, [x for x in self.data if x < other])
        except:
            return LispList(self.type, [])

    def __ge__(self, other):
        try:
            return LispList(self.type, [x for x in self.data if x >= other])
        except:
            return LispList(self.type, [])

    def __le__(self, other):
        try:
            return LispList(self.type, [x for x in self.data if x <= other])
        except:
            return LispList(self.type, [])

    def __eq__(self, other):
        try:
            return LispList(self.type, [x for x in self.data if x == other])
        except:
            return LispList(self.type, [])

    # ========== SEQUENCE PROTOCOL ==========

    def __getitem__(self, idx):
        return self.data[idx]

    def __len__(self):
        return len(self.data)

    def __iter__(self):
        return iter(self.data)

    # ========== LISP-STYLE PRINTING ==========

    def __repr__(self):
        """Lisp-style representation: (type elem1 elem2 ...)"""
        return self.to_string(compact=True)

    def __str__(self):
        """Pretty-printed Lisp representation."""
        return self.to_string(compact=False)

    def to_string(self, compact: bool = False, indent: int = 0) -> str:
        """
        Lisp-style string representation.
        Format: (type elem1 elem2 ...)
        Where type is always the first element.
        """
        indent_str = " " * indent

        # Empty list
        if len(self.data) == 0:
            return f"({self.type.name})"

        # Check if we have nested lists
        has_nested = any(isinstance(item, (list, LispList)) for item in self.data)

        if not has_nested:
            # Simple 1D list
            elements = [self.type.name]  # Type is first element

            for item in self.data:
                if isinstance(item, LispList):
                    elements.append(item.to_string(compact, indent + 1))
                else:
                    # Atom - represent appropriately
                    if isinstance(item, str):
                        elements.append(f'"{item}"')
                    elif isinstance(item, float):
                        # Clean float formatting
                        if item.is_integer():
                            elements.append(str(int(item)))
                        else:
                            elements.append(f"{item:g}")
                    else:
                        elements.append(str(item))

            if compact:
                return f"({' '.join(elements)})"
            else:
                line = f"({' '.join(elements)})"
                if len(line) > 60:  # Wrap long lines
                    return (f"(\n" +
                            f"{indent_str}  {self.type.name}\n" +
                            f"{indent_str}  " +
                            f"\n{indent_str}  ".join(elements[1:]) +
                            f"\n{indent_str})")
                return line

        # Handle nested structures
        else:
            if compact:
                return f"({self.type.name} ...)"
            else:
                lines = [f"({self.type.name}"]

                for item in self.data:
                    if isinstance(item, LispList):
                        lines.append(f"{indent_str}  {item.to_string(compact, indent + 1)}")
                    elif isinstance(item, list):
                        # Convert plain list to LispList for printing
                        sub = LispList(self.type, item)
                        lines.append(f"{indent_str}  {sub.to_string(compact, indent + 1)}")
                    else:
                        if isinstance(item, str):
                            lines.append(f'{indent_str}  "{item}"')
                        else:
                            lines.append(f"{indent_str}  {item}")

                lines.append(f"{indent_str})")
                return "\n".join(lines)

    def prototype(self, indent: int = 0) -> str:
        """Show structure with type placeholders."""
        indent_str = " " * indent
        lines = [f"{indent_str}({self.type.name}"]

        if len(self.data) == 0:
            return f"{indent_str}({self.type.name})"

        for item in self.data:
            if isinstance(item, LispList):
                lines.append(item.prototype(indent + 1))
            elif isinstance(item, list):
                sub = LispList(self.type, item)
                lines.append(sub.prototype(indent + 1))
            else:
                # Atom type placeholder
                if isinstance(item, (int, float)):
                    lines.append(f"{indent_str}  <number>")
                elif isinstance(item, str):
                    lines.append(f"{indent_str}  <string>")
                else:
                    lines.append(f"{indent_str}  <atom>")

        lines.append(f"{indent_str})")
        return "\n".join(lines)

    def debug(self):
        """Print debug information."""
        print(f"=== LispList: {self.type.name} ===")
        print(f"Type: {self.type.name}")
        print(f"Ancestors: {[a.name for a in get_ancestors(self.type)]}")
        print(f"Properties: {self.type.properties}")
        print(f"Length: {len(self.data)}")
        print(f"Data: {self.data}")
        print(f"Lisp: {self}")


# ============================================
# DEMONSTRATION
# ============================================

if __name__ == "__main__":
    print("=" * 60)
    print("LISP-STYLE TYPE SYSTEM")
    print("=" * 60)

    # Create lists using lisp factory
    nums = lisp.number(1, 2, 3, 4, 5)
    words = lisp.string("hello", "world", "lisp")
    matrix = lisp.vector(
        lisp.number(1, 2, 3),
        lisp.number(4, 5, 6),
        lisp.number(7, 8, 9)
    )
    mixed = lisp.list(
        lisp.number(10, 20, 30),
        42,
        "data",
        lisp.string("a", "b", "c")
    )

    print("\n1. BASIC LIST CREATION")
    print("-" * 40)
    print(f"nums = {nums}")
    print(f"words = {words}")
    print(f"mixed = {mixed}")

    print("\n2. NESTED STRUCTURES")
    print("-" * 40)
    print(f"matrix =\n{matrix}")

    print("\n3. LISP OPERATIONS")
    print("-" * 40)
    print(f"nums.car = {nums.car}")
    print(f"nums.cdr = {nums.cdr}")
    print(f"nums.cadr = {nums.cadr}")

    print("\n4. TYPE INSPECTION")
    print("-" * 40)
    print(f"nums is number? {nums.isinstance('number')}")
    print(f"nums is atom? {nums.isinstance('atom')}")
    print(f"nums is object? {nums.isinstance('object')}")
    print(f"nums is string? {nums.isinstance('string')}")
    print(f"nums.get_property('numeric') = {nums.get_property('numeric')}")
    print(f"nums.get_property('encoding', 'n/a') = {nums.get_property('encoding', 'n/a')}")

    print("\n5. TRANSFORMATIONS")
    print("-" * 40)
    print(f"Original: {nums}")
    print(f"Reversed: {nums.reverse()}")
    print(f"Negated: {-nums}")
    print(f"Squared: {nums ** 2}")

    print("\n6. FILTERING")
    print("-" * 40)
    print(f"Original: {nums}")
    print(f"> 3: {nums > 3}")
    print(f"< 3: {nums < 3}")

    print("\n7. MATRIX OPERATIONS")
    print("-" * 40)
    print(f"Original matrix:\n{matrix}")
    print(f"\nTranspose (T):\n{matrix.T}")
    print(f"\nReverse rows:\n{matrix.reverse(axis=0)}")
    print(f"\nReverse columns:\n{matrix.reverse(axis=1)}")

    print("\n8. PROTOTYPES")
    print("-" * 40)
    print("Matrix prototype:")
    print(matrix.prototype())

    print("\nMixed prototype:")
    print(mixed.prototype())

    print("\n9. TYPE HIERARCHY")
    print("-" * 40)
    print(f"number ancestors: {[a.name for a in get_ancestors(number)]}")
    print(f"integer ancestors: {[a.name for a in get_ancestors(integer)]}")
    print(f"integer is number? {is_subtype_of(integer, 'number')}")

    print("\n10. DEBUG INFO")
    print("-" * 40)
    nums.debug()