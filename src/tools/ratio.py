from fractions import Fraction

class Ratio(Fraction):
    def __new__(cls, numerator=0, denominator=None):
        if denominator is None:
            return super().__new__(cls, numerator)
        return super().__new__(cls, numerator, denominator)

    __str__ = __repr__ = lambda self: f"{self.numerator}/{self.denominator}"


ZERO = Ratio(0)
ONE = Ratio(1)
