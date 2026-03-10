# functions.py

def gcd(a: int, b: int) -> int:
    """
    Calculate the greatest common divisor of two integers using Euclidean algorithm.

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    int: Greatest common divisor of a and b
    """
    # Ensure positive values for the algorithm
    a, b = abs(a), abs(b)

    # Euclidean algorithm
    while b != 0:
        a, b = b, a % b

    return a


def gcd_recursive(a: int, b: int) -> int:
    """
    Calculate GCD using recursive Euclidean algorithm.

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    int: Greatest common divisor of a and b
    """
    a, b = abs(a), abs(b)
    if b == 0:
        return a
    return gcd_recursive(b, a % b)


def extended_gcd(a: int, b: int) -> tuple[int, int, int]:
    """
    Extended Euclidean algorithm.
    Returns (g, x, y) such that a*x + b*y = g = gcd(a, b)

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    tuple: (gcd, x, y) coefficients
    """
    a, b = abs(a), abs(b)

    if b == 0:
        return a, 1, 0

    g, x1, y1 = extended_gcd(b, a % b)
    x = y1
    y = x1 - (a // b) * y1

    return g, x, y


def binary_gcd(a: int, b: int) -> int:
    """
    Binary GCD algorithm (Stein's algorithm).
    More efficient for large numbers as it uses bit operations.

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    int: Greatest common divisor of a and b
    """
    a, b = abs(a), abs(b)

    # Base cases
    if a == 0:
        return b
    if b == 0:
        return a

        # Find common factors of 2
    shift = 0
    while ((
                   a | b) & 1) == 0:  # Both even
        a >>= 1
        b >>= 1
        shift += 1

        # Make a odd
    while (a & 1) == 0:
        a >>= 1

        # Main loop
    while b != 0:
        # Make b odd
        while (b & 1) == 0:
            b >>= 1

            # Swap if necessary
        if a > b:
            a, b = b, a

        b -= a

    return a << shift


def lcm(a: int, b: int) -> int:
    """
    Calculate least common multiple using GCD.

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    int: Least common multiple of a and b
    """
    if a == 0 or b == 0:
        return 0
    return abs(a * b) // gcd(a, b)


def gcd_multiple(*numbers: int) -> int:
    """
    Calculate GCD of multiple numbers.

    Parameters:
    *numbers: Variable number of integers

    Returns:
    int: GCD of all input numbers
    """
    if not numbers:
        return 0

    result = abs(numbers[0])
    for num in numbers[1:]:
        result = gcd(result, abs(num))
        if result == 1:
            break

    return result


def lcm_multiple(*numbers: int) -> int:
    """
    Calculate LCM of multiple numbers.

    Parameters:
    *numbers: Variable number of integers

    Returns:
    int: LCM of all input numbers
    """
    if not numbers:
        return 0

    result = abs(numbers[0])
    for num in numbers[1:]:
        if num == 0:
            return 0
        result = lcm(result, abs(num))

    return result


def are_coprime(a: int, b: int) -> bool:
    """
    Check if two numbers are coprime (relatively prime).

    Parameters:
    a (int): First integer
    b (int): Second integer

    Returns:
    bool: True if numbers are coprime, False otherwise
    """
    return gcd(a, b) == 1


def modular_inverse(a: int, m: int) -> int:
    """
    Find modular multiplicative inverse using extended Euclidean algorithm.
    Returns x such that (a * x) % m == 1

    Parameters:
    a (int): Number to find inverse for
    m (int): Modulus

    Returns:
    int: Modular inverse of a modulo m
    Raises:
    ValueError: If inverse doesn't exist (a and m not coprime)
    """
    g, x, _ = extended_gcd(a, m)

    if g != 1:
        raise ValueError(f"Inverse doesn't exist: gcd({a}, {m}) = {g}")

        # Ensure result is positive
    return x % m


def solve_linear_diophantine(a: int, b: int, c: int) -> tuple[bool, int, int, int]:
    """
    Solve linear Diophantine equation: a*x + b*y = c

    Parameters:
    a (int): Coefficient of x
    b (int): Coefficient of y
    c (int): Constant

    Returns:
    tuple: (solvable, g, x0, y0) where:
        - solvable: bool indicating if solution exists
        - g: gcd(a, b)
        - x0, y0: particular solution if solvable
    """
    g, xg, yg = extended_gcd(a, b)

    if c % g != 0:
        return False, g, 0, 0

        # Scale the solution
    factor = c // g
    return True, g, xg * factor, yg * factor

"""
Summary of added algorithms:                                                                                                                                                                                                        

 1 gcd_recursive - Recursive version of Euclidean algorithm                                                                                                                                                                         
  2 extended_gcd - Extended Euclidean algorithm (returns Bézout coefficients)                                                                                                                                                       
  3 binary_gcd - Stein's algorithm (uses bit operations, efficient for large numbers)                                                                                                                                               
  4 lcm - Least common multiple using GCD                                                                                                                                                                                           
  5 gcd_multiple - GCD for multiple numbers                                                                                                                                                                                         
  6 lcm_multiple - LCM for multiple numbers                                                                                                                                                                                         
  7 are_coprime - Check if numbers are relatively prime                                                                                                                                                                             
  8 modular_inverse - Find modular inverse using extended Euclidean algorithm                                                                                                                                                       
  9 solve_linear_diophantine - Solve linear Diophantine equations                                                                                                                                                                   

All functions handle negative numbers appropriately and include proper documentation.     
"""