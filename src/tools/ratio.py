from fractions import Fraction


class Ratio(Fraction):

    def __new__(cls, numerator=0, denominator=1):
        return super().__new__(cls, numerator, denominator)

    __str__ = __repr__ = lambda self: f"{self.numerator}/{self.denominator}"


ZERO = Ratio(0)
ONE = Ratio(1)
