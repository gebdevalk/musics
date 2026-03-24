from fractions import Fraction


class Ratio(Fraction):

    def __new__(cls, num=0, denom=1):
        return super().__new__(cls, num, denom)

    __str__ = __repr__ = lambda self: f"{super().numerator}/{super().denominator}"


ZERO = Ratio(0)
ONE = Ratio(1)
