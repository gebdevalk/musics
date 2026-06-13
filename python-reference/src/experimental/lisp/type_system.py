from typedpy import Structure, String, Array, Field, ImmutableField
from typing import Optional


class Type(Structure):
    """Immutable type system with name, parent, and containment"""
    name: String(pattern='^[A-Z][a-zA-Z0-9_]*$')  # Must start with capital
    is_type: Optional['Type'] = None  # Parent/super type
    has: Optional[Array['Type']] = None  # Contained types (None = atomic)

    class _immutable:
        pass  # Makes entire type immutable

    def __str__(self):
        parent = self.is_type.name if self.is_type else "Ø"
        contained = [t.name for t in self.has] if self.has else "None" if self.has is None else "[]"
        return f"Type[{self.name}] <: {parent} has: {contained}"


# Create type hierarchy
# Object = Type(name="Object", has=[])  # Root, can contain
# Atom = Type(name="Atom", is_type=Object, has=None)  # Atomic (non-modifiable)
# Number = Type(name="Number", is_type=Atom, has=None)  # Primitive number
# String = Type(name="String", is_type=Atom, has=None)  # Primitive string
# List = Type(name="List", is_type=Object, has=[Atom])  # Can contain Atoms
# Matrix = Type(name="Matrix", is_type=List, has=[Number])  # Can contain Numbers
