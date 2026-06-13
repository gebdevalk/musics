"""
Composite Pattern:

Component
├── Iterable (interface)
│ ├── StructuralIterable
│ │ ├── Chain
│ │ ├── Aggregate
│ │ ├── Chord
│ │ ├── Set
│ │ └── Bag
│ ├── GenerativeIterable
│   ├── Sequence
│   └── Algorithm (Atoms only)
│
└── NonIterable (interface)
├── Context
├── Atom
├── Concurrent
└── Tran

Other classes:

Transient
Data
"""

from abc import ABC, abstractmethod
from collections import UserList
from dataclasses import dataclass
from typing import Optional, List, Any, Iterable
from concurrent.futures import ThreadPoolExecutor, as_completed


# ============================================================================
# CONTEXT - The only dataclass (mutable, can have parent)
# ============================================================================

@dataclass
class Context(ABC):
    """Mutable context that can chain to parent"""
    parent: Optional['Context'] = None
    debug: bool = False
    version: str = "1.0"
    environment: str = "development"
    indent: int = 0

    def get(self, name: str):
        """Get property, traversing parent chain if needed"""
        if hasattr(self, name):
            return getattr(self, name)
        return self.parent.get(name) if self.parent else None


# ============================================================================
# COMPONENT - Base class (regular class)
# ============================================================================

class Component(Context):
    """Base component with context reference"""

    def __init__(self, ctx: Optional[Context] = None):
        self.ctx = ctx

    @abstractmethod
    def operation(self) -> Any:
        pass

    def _debug(self, msg: str, indent=0):
        """Print debug message if context debug is enabled"""
        if self.ctx and self.ctx.get('debug'):
            indent_str = "  " * (self.ctx.get('indent', 0) + indent)
            print(f"{indent_str}{msg}")


# ============================================================================
# ATOM - Leaf node
# ============================================================================

class Atom(Component):
    """Simple leaf node with a value"""

    def __init__(self, value: Any, name: str = "", ctx: Optional[Context] = None):
        super().__init__(ctx)
        self.value = value
        self.name = name or str(value)

    def operation(self) -> Any:
        self._debug(f"⚫ {self.name}: {self.value}", 1)
        return self.value

    def __repr__(self):
        return f"Atom({self.name})"


# ============================================================================
# COMPOSITE - Base for containers
# ============================================================================

class Composite(Iterable, Component):
    """Base for components that contain children"""

    def __init__(self, ctx: Optional[Context] = None):
        super().__init__(ctx)
        self.children: List[Component] = []

    def add(self, child: Component):
        """Add child, propagating context if needed"""
        if not child.ctx:
            child.ctx = self.ctx
        self.children.append(child)
        self._debug(f"  ➕ Added {child}")

    def remove(self, child: Component):
        if child in self.children:
            self.children.remove(child)

    @abstractmethod
    def operation(self) -> Any:
        pass


# ============================================================================
# CHAIN - Sequential processor
# ============================================================================

class Chain(Composite):
    """Process children in sequence"""

    def __init__(self, name: str = "", ctx: Optional[Context] = None):
        super().__init__(ctx)
        self.name = name or "Chain"

    def operation(self) -> List[Any]:
        self._debug(f"🔗 {self.name} start")
        if self.ctx: self.ctx.indent += 1

        results = [c.operation() for c in self.children]

        if self.ctx: self.ctx.indent -= 1
        self._debug(f"🔗 {self.name} done")
        return results

    def __repr__(self):
        return f"Chain({self.name}, {len(self.children)})"


# ============================================================================
# CONCURRENT - Parallel processor
# ============================================================================

class Concurrent(Composite):
    """Process children in parallel"""

    def __init__(self, name: str = "", ctx: Optional[Context] = None):
        super().__init__(ctx)
        self.name = name or "Concurrent"

    def operation(self) -> List[Any]:
        self._debug(f"⚡ {self.name} start")
        if self.ctx: self.ctx.indent += 1

        with ThreadPoolExecutor() as ex:
            futures = [ex.submit(c.operation) for c in self.children]
            results = [f.result() for f in as_completed(futures)]

        if self.ctx: self.ctx.indent -= 1
        self._debug(f"⚡ {self.name} done")
        return results

    def __repr__(self):
        return f"Concurrent({self.name}, {len(self.children)})"


# ============================================================================
# COMPOUND - Atom that contains atoms
# ============================================================================

class Aggregate(Iterable, Atom):
    """Atom that can contain other atoms (only atoms!)"""

    def __init__(self, name: str = "", ctx: Optional[Context] = None):
        super().__init__(None, name, ctx)
        self.name = name or "Compound"
        self._atoms: List[Atom] = []

    def add(self, atom: Atom):
        """Add an atom (type-safe)"""
        if not isinstance(atom, Atom):
            raise TypeError(f"Compound only accepts Atoms, got {type(atom).__name__}")
        if not atom.ctx:
            atom.ctx = self.ctx
        self._atoms.append(atom)
        self._debug(f"  📦 Added {atom}")

    def operation(self) -> Any:
        self._debug(f"📦 {self.name} combining {len(self._atoms)} atoms")
        if self.ctx: self.ctx.indent += 1

        results = [a.operation() for a in self._atoms]

        if self.ctx: self.ctx.indent -= 1
        self._debug(f"📦 {self.name} done")

        return {
            'name': self.name,
            'count': len(results),
            'results': results,
            'combined': self._combine(results)
        }

    def _combine(self, results):
        """Override for custom combination"""
        return results

    def __repr__(self):
        return f"Compound({self.name}, {len(self._atoms)})"


# ============================================================================
# SPECIALIZED COMPOUNDS (one-liners)
# ============================================================================

# class SumCompound(Compound):
#     def _combine(self, results): return sum(r for r in results if isinstance(r, (int, float)))
#
#
# class ConcatCompound(Compound):
#     def _combine(self, results): return ''.join(str(r) for r in results)


# ============================================================================
# DEMO
# ============================================================================

def demo():
    print("=" * 50)
    print("COMPACT COMPOSITE PATTERN")
    print("=" * 50)

    # Create context chain
    root = Context(debug=True, version="2.0")
    child = Context(parent=root, debug=False)
    grand = Context(parent=child, version="2.1")

    print(f"\n📋 Context chain:")
    print(f"  Root: debug={root.debug}, version={root.version}")
    print(f"  Child: debug={child.debug}, version={child.version}")
    print(f"  Grand: debug={grand.get('debug')}, version={grand.get('version')}")

    # Create atoms
    a1 = Atom(42, "answer", grand)
    a2 = Atom("hello", "greeting")
    a3 = Atom(3.14, "pi", root)
    a4 = Atom(100, "max")

    # Build structures
    print(f"\n🔹 Building hierarchy:")

    chain = Chain("Process", root)
    chain.add(a1)
    chain.add(a2)  # gets root ctx
    chain.add(a3)

    compound = Compound("Root", grand)
    compound.add(a1)
    compound.add(Atom("nested", "n"))

    # Type safety demo
    try:
        compound.add(chain)  # Should fail
    except TypeError as e:
        print(f"  ✅ Type safety: {e}")

    # Execute
    print(f"\n🔹 Chain operation (root.debug=True):")
    chain.operation()

    print(f"\n🔹 Compound operation (grand.debug=False):")
    compound.operation()


if __name__ == "__main__":
    demo()