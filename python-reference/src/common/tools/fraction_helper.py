from fractions import Fraction

def fraction_from_string(s: str) -> Fraction:
    """Create Fraction from string like '1/4', '4', '4.' (dotted), '3/8'"""
    if '/' in s:
        num, den = s.split('/')
        return Fraction(int(num), int(den))
    elif '.' in s:
        return fraction_from_dotted(s)
    else:
        return Fraction(1, int(s))


def fraction_from_dotted(s: str)-> Fraction:
    """
    Parse strings like '4', '4.', '4..', '8.', '2..' into Ratio.
    """
    # split into base and dots
    base = ""
    dots = 0
    for ch in s:
        if ch == '.':
            dots += 1
        else:
            base += ch

    if not base.isdigit():
        raise ValueError(f"Invalid dotted duration: {s}")

    denominator = int(base)
    # base value: 1/denominator
    value = Fraction(1, denominator)
    # add dots: 1/denom * (1/2 + 1/4 + 1/8 + ...)
    add = value
    for _ in range(dots):
        add = add / 2
        value = value + add

    return Fraction(value.numerator, value.denominator)


ZERO = Fraction(0)
ONE = Fraction(1)


import math
from fractions import Fraction


class MutableFraction(Fraction):
    __slots__ = ()

    def __new__(cls, numerator=0, denominator=1):
        return super().__new__(cls, numerator, denominator)

    def __init__(self, numerator=0, denominator=1):
        # Fraction is immutable by design, so all setup happens in __new__.
        # Nothing extra needed here.
        pass

    def _set_value(self, num, den):
        if den == 0:
            raise ZeroDivisionError("denominator cannot be zero")
        g = math.gcd(abs(num), abs(den))
        num //= g
        den //= g
        if den < 0:
            num = -num
            den = -den
        self._numerator = num
        self._denominator = den

    def __iadd__(self, other):
        if isinstance(other, (int, Fraction)):
            other = Fraction(other)
            new_num = self._numerator * other.denominator + other.numerator * self._denominator
            new_den = self._denominator * other.denominator
        else:
            return NotImplemented
        self._set_value(new_num, new_den)
        return self

    def __isub__(self, other):
        if isinstance(other, (int, Fraction)):
            other = Fraction(other)
            new_num = self._numerator * other.denominator - other.numerator * self._denominator
            new_den = self._denominator * other.denominator
        else:
            return NotImplemented
        self._set_value(new_num, new_den)
        return self

    def __imul__(self, other):
        if isinstance(other, (int, Fraction)):
            other = Fraction(other)
            new_num = self._numerator * other.numerator
            new_den = self._denominator * other.denominator
        else:
            return NotImplemented
        self._set_value(new_num, new_den)
        return self

    def __itruediv__(self, other):
        if isinstance(other, (int, Fraction)):
            other = Fraction(other)
            if other.numerator == 0:
                raise ZeroDivisionError("division by zero")
            new_num = self._numerator * other.denominator
            new_den = self._denominator * other.numerator
        else:
            return NotImplemented
        self._set_value(new_num, new_den)
        return self

    __hash__ = None  # unhashable because mutable

def main():
    tests = [
        "4",
        "4.",
        "4..",
        "8",
        "8.",
        "2..",
        "16...",
    ]

    print("Testing Ratio.from_dotted:")
    for s in tests:
        r = fraction_from_string(s)
        print(f"{s:5} → {r} (={float(r)})")

    tests2 = [
        "1/4",
        "2/4",
        "3/4",
        "9/16",
        "47/37",
    ]

    print("Testing Ratio.from_string:")
    for s in tests2:
        r = fraction_from_string(s)
        print(f"{s:5} → {r} (={float(r)})")

if __name__ == "__main__":
    main()