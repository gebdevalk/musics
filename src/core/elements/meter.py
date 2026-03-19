from typing import Protocol

# --- Base class ---

class Meter:
    def __init__(self, number: int, unit: int):
        self.number = number
        self.unit = unit

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Meter):
            return False
        return self.number == other.number and self.unit == other.unit

    def __hash__(self) -> int:
        return hash((31 * self.number + self.unit))

    def __repr__(self) -> str:
        return f"M({self.number}/{self.unit})"

    def to_lilypond(self) -> str:
        return f"\\time {self.number}/{self.unit}"


# --- Subclasses ---

class DivisiveMeter(Meter):
    def __eq__(self, other: object) -> bool:
        if not isinstance(other, DivisiveMeter):
            return False
        return super().__eq__(other)

    def __hash__(self) -> int:
        return super().__hash__()


class AdditiveMeter(Meter):
    def __init__(self, *args: int):
        divs = list(args[:-1])
        unit = args[-1]
        super().__init__(sum(divs), unit)
        self.divs = divs

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, AdditiveMeter):
            return False
        return super().__eq__(other)

    def __hash__(self) -> int:
        return super().__hash__()

    def __repr__(self) -> str:
        return f"M({self.number}/{self.unit})"

    def to_lilypond(self) -> str:
        return f"\\time {self.number}/{self.unit}"


# --- Marker mixins ---

class Duple: pass
class Triple: pass
class Quaduple: pass
class Binary: pass
class Ternary: pass
class Quaternary: pass


# --- Singleton instances (Kotlin objects) ---

class _M22(DivisiveMeter, Duple):
    def __init__(self): super().__init__(2, 2)

class _M24(DivisiveMeter, Duple):
    def __init__(self): super().__init__(2, 4)

class _M28(DivisiveMeter, Duple):
    def __init__(self): super().__init__(2, 8)

class _M32(DivisiveMeter, Triple):
    def __init__(self): super().__init__(3, 2)

class _M34(DivisiveMeter, Triple):
    def __init__(self): super().__init__(3, 4)

class _M38(DivisiveMeter, Triple):
    def __init__(self): super().__init__(3, 8)

class _M42(DivisiveMeter, Quaduple):
    def __init__(self): super().__init__(4, 2)

class _M44(DivisiveMeter, Quaduple):
    def __init__(self): super().__init__(4, 4)

class _M48(DivisiveMeter, Quaduple):
    def __init__(self): super().__init__(4, 4)

class _M68(DivisiveMeter, Duple):
    def __init__(self): super().__init__(6, 8)

class _M98(DivisiveMeter, Triple):
    def __init__(self): super().__init__(9, 8)

class _M232(AdditiveMeter, Binary):
    def __init__(self): super().__init__(2, 3, 2)

class _M234(AdditiveMeter, Binary):
    def __init__(self): super().__init__(2, 3, 4)

class _M238(AdditiveMeter, Binary):
    def __init__(self): super().__init__(2, 3, 8)

class _M322(AdditiveMeter, Binary):
    def __init__(self): super().__init__(3, 2, 2)

class _M324(AdditiveMeter, Binary):
    def __init__(self): super().__init__(3, 2, 4)

class _M328(AdditiveMeter, Binary):
    def __init__(self): super().__init__(3, 2, 8)

class _M2232(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(2, 2, 3, 2)

class _M2234(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(2, 2, 3, 4)

class _M2238(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(2, 2, 3, 8)

class _M2322(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(2, 3, 2, 2)

class _M2324(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(2, 3, 2, 4)

class _M3222(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(3, 2, 2, 2)

class _M3224(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(3, 2, 2, 4)

class _M3228(AdditiveMeter, Ternary):
    def __init__(self): super().__init__(3, 2, 2, 8)

class _M23232(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(2, 3, 2, 3, 2)

class _M23234(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(2, 3, 2, 3, 4)

class _M23238(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(2, 3, 2, 3, 8)

class _M32322(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(3, 2, 3, 2, 2)

class _M32324(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(3, 2, 3, 2, 4)

class _M32328(AdditiveMeter, Quaternary):
    def __init__(self): super().__init__(3, 2, 3, 2, 8)


# Singleton instances
M22  = _M22()
M24  = _M24()
M28  = _M28()
M32  = _M32()
M34  = _M34()
M38  = _M38()
M42  = _M42()
M44  = _M44()
M48  = _M48()
M68  = _M68()
M98  = _M98()

M232 = _M232()
M234 = _M234()
M238 = _M238()
M322 = _M322()
M324 = _M324()
M328 = _M328()

M2232 = _M2232()
M2234 = _M2234()
M2238 = _M2238()
M2322 = _M2322()
M2324 = _M2324()
M3222 = _M3222()
M3224 = _M3224()
M3228 = _M3228()

M23232 = _M23232()
M23234 = _M23234()
M23238 = _M23238()
M32322 = _M32322()
M32324 = _M32324()
M32328 = _M32328()


# --- Helper ---

def to_meter(s: str) -> Meter:
    # Expects format "M(10/8)"
    inner = s[2:-1]  # strip "M(" and ")"
    number, unit = inner.split("/")
    return Meter(int(number), int(unit))
