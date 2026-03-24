
from typing import Callable, Dict, Tuple, Type


# ============================================================
# 1. Reduced Domain Classes (structural types)
# ============================================================

# class Leaf:
#     def __init__(self, kind: str, **data):
#         self.kind = kind
#         self.data = data
#
#     def __repr__(self):
#         return f"Leaf(kind={self.kind}, data={self.data})"
#
#
# class PC:
#     def __init__(self, kind: str, **data):
#         self.kind = kind
#         self.data = data
#
#     def __repr__(self):
#         return f"PC(kind={self.kind}, data={self.data})"
#
#
# class CC:
#     def __init__(self, kind: str, **data):
#         self.kind = kind
#         self.data = data
#
#     def __repr__(self):
#         return f"CC(kind={self.kind}, data={self.data})"
#
#
# class Composite:
#     def __init__(self, kind: str, children):
#         self.kind = kind
#         self.children = children
#
#     def __repr__(self):
#         return f"Composite(kind={self.kind}, children={self.children})"


# ============================================================
# 2. Multimethod System (dispatch on class + kind)
# ============================================================

class MultiMethod:
    def __init__(self):
        # registry[(class, kind)] = function
        self.registry: Dict[Tuple[Type, str], Callable] = {}

    def register(self, cls: Type, kind: str):
        """Decorator to register a handler for (class, kind)."""
        def decorator(func):
            self.registry[(cls, kind)] = func
            return func
        return decorator

    def __call__(self, obj):
        key = (type(obj), obj.kind)

        # Direct match
        if key in self.registry:
            return self.registry[key](obj)

        # No match found
        raise TypeError(f"No multimethod handler for {key}")


# Create a multimethod instance
process = MultiMethod()


# ============================================================
# 3. Register Handlers
# ============================================================

@process.register(Leaf, "note")
def _(x: Leaf):
    return f"Process NOTE: pitch={x.data.get('pitch')}"


@process.register(Leaf, "rest")
def _(x: Leaf):
    return "Process REST"


@process.register(Leaf, "chord")
def _(x: Leaf):
    return f"Process CHORD: pitches={x.data.get('pitches')}"


@process.register(Composite, "sequence")
def _(x: Composite):
    return [process(child) for child in x.children]


# ============================================================
# 4. Example Usage
# ============================================================

if __name__ == "__main__":
    print(process(Leaf("note", pitch=60)))
    print(process(Leaf("rest")))
    print(process(Leaf("chord", pitches=[60, 64, 67])))

    tree = Composite("sequence", [
        Leaf("note", pitch=62),
        Leaf("rest"),
        Leaf("chord", pitches=[65, 69, 72])
    ])

    print(process(tree))
