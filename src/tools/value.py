from fractions import Fraction


class Value(Fraction):
    def __repr__(self):
        return f"{self.numerator}/{self.denominator}"

    def __str__(self):
        return f"{self.numerator}/{self.denominator}"
