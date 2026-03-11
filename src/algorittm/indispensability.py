# indispensability.py

# import math
from fractions import Fraction
from functools import reduce
import operator

def psi_fractions(q_list):
    prod_all_q = 1
    for val in q_list:
        prod_all_q *= val
        # return [Fraction(psi(n, q_list), prod_all_q) for n in range(prod_all_q + 1)]
        result = [psi(n, q_list) for n in range(prod_all_q + 1)]
    return result[1:]


def psi(n, quotients):
    """
    Implements the psi(n) function from the image.
    n: The number to process
    quotients: The bases [q1, q2, ..., ql]
    """
    product = reduce(operator.mul, quotients, 1)
    l = len(quotients) # order or number of divisions
    m = (n - 2) % product

    accu = 0

    # Step 2: Sum over h from 0 to h
    for h in range(0, l):

        # Calculate the weight: Product of q_{h-i} for i = 1 to (h - h + 1)
        weight = 1
        for i in range(0, (l - h - 1)):
            weight *= quotients[i]

        # Calculate the divisor: Product of q_{h+h-k} for k = 0 to h
        divisor = 1
        for k in range(0, h):
            divisor *= quotients[l - h]

        digit = (m // divisor) % quotients[h]

        # Add to total: weight * digit
        accu += weight * digit

    return accu

def main():
    q_list = [2, 2]
    fracs = psi_fractions(q_list)
    # prod = 6, so you get fractions for n = 0..6
    for n, f in enumerate(fracs):
        print(f"n={n}: {f}")

if __name__ == "__main__":
    # main()
    # print(psi(0, [2,2]))
    print(psi_fractions([3,3,2]))
    # print(psi_fractions([2,2,2,2,2,2,]))
    # print(psi(1, [2, 2]))
    # print(psi(2, [2, 2]))
    # print(psi(3, [2, 2]))
    # for x in range(0,3):
    #     print(x)