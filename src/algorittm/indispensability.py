# indispensability.py
import math
from tools.ratio import Ratio

def psi_fractions(q_list):
    prod_all_q = 1
    for val in q_list:
        prod_all_q *= val
        result = [psi(n, q_list) for n in range(prod_all_q + 1)]
    return result[1:]


def psi(n, quotients):
    product = math.prod(quotients)
    m = (n - 2) % product
    accu = 0
    for h in range(len(quotients)):
        weight  = math.prod(quotients[:h])           # bases MORE significant than h
        divisor = math.prod(quotients[h+1:])         # bases LESS significant than h
        digit   = (m // divisor) % quotients[h]
        accu   += weight * digit
    return Ratio(accu + 1, product)

# exponential scaling
# weight = adherence ** (max_psi - psi_value)

# soft max
def beat_probabilities(psi_values, adherence):
    scaled = [v * adherence for v in psi_values]
    exps = [math.exp(v) for v in scaled]
    total = sum(exps)
    return [e / total for e in exps]


def main():
    q_list = [2, 2]
    fracs = psi_fractions(q_list)
    # prod = 6, so you get fractions for n = 0..6
    for n, f in enumerate(fracs):
        print(f"n={n}: {f}")

# filter
def beat_window(psi_values, min_psi, max_psi):
    return [i for i, v in enumerate(psi_values) if min_psi <= v <= max_psi]

if __name__ == "__main__":
    # main()
    # print(psi(0, [2,2]))
    print(psi_fractions([2,3,2]))
    # print(psi_fractions([2,2,2,2,2,2,]))
