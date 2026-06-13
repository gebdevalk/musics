# rhythm.py

"""
Here are some algorithms for generating rhythmic patterns that I can implement:

 1 Euclidean Rhythm Algorithm - Distributes beats as evenly as possible (Bjorklund's algorithm)
 2 Fibonacci Rhythm - Uses Fibonacci sequence for rhythmic patterns
  3 Prime Number Rhythm - Uses prime numbers for irregular but structured patterns
  4 Lindenmayer System (L-system) Rhythm - Uses string rewriting for fractal-like patterns
  5 Markov Chain Rhythm - Generates patterns based on transition probabilities
  6 Cellular Automaton Rhythm - Uses rules like Conway's Game of Life
  7 Binary Decomposition Rhythm - Breaks down numbers into binary patterns
  8 Continued Fraction Rhythm - Uses continued fraction expansions
  9 Modular Arithmetic Rhythm - Patterns based on modulo operations
 10 Random Walk Rhythm - Controlled random generation with constraints

Which of these would you like me to implement first? The Euclidean Rhythm Algorithm is particularly famous and widely used in algorithmic music composition.
"""

# rhythm.py
"""                                                                                                                                                                                                                                 
Rhythmic pattern generation algorithms for algorithmic music composition.                                                                                                                                                           
"""

import math
import random
from typing import List, Tuple, Optional, Union
from itertools import accumulate
from fractions import Fraction


def euclidean_rhythm(k: int, n: int, rotation: int = 0) -> List[int]:
    """
    Generate Euclidean rhythm using Bjorklund's algorithm.
    Distributes k beats as evenly as possible among n pulses.

    Parameters:
    k (int): Number of beats (onsets)
    n (int): Total number of pulses (steps)
    rotation (int): Rotate the pattern by this many steps

    Returns:
    List[int]: Binary pattern where 1=beat, 0=rest

    Example:
    >>> euclidean_rhythm(3, 8)
    [1, 0, 0, 1, 0, 0, 1, 0]  # Common tresillo pattern
    """
    if k > n:
        raise ValueError(f"k ({k}) cannot be greater than n ({n})")
    if k < 0 or n <= 0:
        raise ValueError("k must be >= 0 and n must be > 0")

    if k == 0:
        return [0] * n

        # Bjorklund's algorithm
    pattern = [[1] for _ in range(k)] + [[0] for _ in range(n - k)]

    while len(pattern) > 1:
        # Find the shortest sequence
        min_len = min(len(seq) for seq in pattern)
        min_indices = [i for i, seq in enumerate(pattern) if len(seq) == min_len]

        if len(min_indices) == len(pattern):
            break

            # Distribute the shortest sequences among the others
        for i in range(len(min_indices)):
            target_idx = len(pattern) - 1 - i
            if target_idx < 0 or target_idx >= len(pattern):
                continue
            pattern[target_idx].extend(pattern[min_indices[i]])

            # Remove the distributed sequences
        pattern = [seq for i, seq in enumerate(pattern) if i not in min_indices]

        # Flatten the pattern
    result = []
    for seq in pattern:
        result.extend(seq)

        # Apply rotation
    if rotation != 0:
        rotation = rotation % len(result)
        result = result[rotation:] + result[:rotation]

    return result


def fibonacci_rhythm(length: int, fib_start: Tuple[int, int] = (0, 1)) -> List[int]:
    """
    Generate rhythmic pattern based on Fibonacci sequence.

    Parameters:
    length (int): Length of the pattern
    fib_start (tuple): Starting values for Fibonacci sequence

    Returns:
    List[int]: Pattern where 1 indicates beat on Fibonacci positions

    Example:
    >>> fibonacci_rhythm(13)
    [1, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0]  # Beats at positions 0,1,3,6,11
    """
    a, b = fib_start
    fib_positions = []

    while a < length:
        fib_positions.append(a)
        a, b = b, a + b

    pattern = [1 if i in fib_positions else 0 for i in range(length)]
    return pattern


def prime_rhythm(length: int, include_one: bool = True) -> List[int]:
    """
    Generate rhythmic pattern based on prime number positions.

    Parameters:
    length (int): Length of the pattern
    include_one (bool): Whether to include position 1 as a beat

    Returns:
    List[int]: Pattern with beats at prime number positions
    """

    def is_prime(num: int) -> bool:
        if num < 2:
            return False
        for i in range(2, int(math.sqrt(num)) + 1):
            if num % i == 0:
                return False
        return True

    prime_positions = []
    for i in range(length):
        if (i == 1 and include_one) or (i > 1 and is_prime(i)):
            prime_positions.append(i)

    pattern = [1 if i in prime_positions else 0 for i in range(length)]
    return pattern


def lindenmayer_rhythm(axiom: str, rules: dict, iterations: int, length: int) -> List[int]:
    """
    Generate rhythmic pattern using Lindenmayer system (L-system).

    Parameters:
    axiom (str): Starting string
    rules (dict): Production rules (e.g., {'A': 'AB', 'B': 'A'})
    iterations (int): Number of iterations to apply rules
    length (int): Desired pattern length (truncates if longer)

    Returns:
    List[int]: Pattern where 'A'=1 (beat), 'B'=0 (rest)

    Example:
    >>> lindenmayer_rhythm('A', {'A': 'AB', 'B': 'A'}, 4, 13)
    [1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 0]  # Fibonacci L-system
    """
    current = axiom

    for _ in range(iterations):
        next_str = []
        for char in current:
            next_str.append(rules.get(char, char))
        current = ''.join(next_str)

        # Convert to binary pattern
    pattern = []
    for char in current[:length]:
        if char == 'A':
            pattern.append(1)
        elif char == 'B':
            pattern.append(0)
        else:
            pattern.append(
                0)  # Default for other characters

    # Pad if needed
    if len(pattern) < length:
        pattern.extend([0] * (length - len(pattern)))

    return pattern[:length]


def markov_rhythm(length: int, transition_matrix: dict,
                  initial_state: str = '0', states: dict = None) -> List[int]:
    """
    Generate rhythmic pattern using Markov chain.

    Parameters:
    length (int): Length of the pattern
    transition_matrix (dict): Markov transition probabilities
        e.g., {'0': {'0': 0.7, '1': 0.3}, '1': {'0': 0.6, '1': 0.4}}
    initial_state (str): Starting state ('0' or '1')
    states (dict): Mapping from state names to output values

    Returns:
    List[int]: Generated pattern
    """
    if states is None:
        states = {'0': 0, '1': 1}

    pattern = []
    current_state = initial_state

    for _ in range(length):
        pattern.append(states[current_state])

        # Choose next state based on transition probabilities
        transitions = transition_matrix[current_state]
        rand_val = random.random()
        cumulative = 0.0

        for next_state, prob in transitions.items():
            cumulative += prob
            if rand_val <= cumulative:
                current_state = next_state
                break

    return pattern


def binary_decomposition_rhythm(number: int, length: Optional[int] = None) -> List[int]:
    """
    Generate rhythmic pattern from binary representation of a number.

    Parameters:
    number (int): Number to convert to binary
    length (int): Desired pattern length (pads with zeros if needed)

    Returns:
    List[int]: Binary pattern (LSB first by default)

    Example:
    >>> binary_decomposition_rhythm(5, 8)
    [1, 0, 1, 0, 0, 0, 0, 0]  # 5 in binary is 101
    """
    if number < 0:
        raise ValueError("Number must be non-negative")

        # Convert to binary (LSB first)
    binary_str = bin(number)[
        2:]  # Remove '0b' prefix
    pattern = [int(bit) for bit in reversed(
        binary_str)]  # LSB first

    # Pad or truncate to desired length
    if length is not None:
        if len(pattern) < length:
            pattern.extend([0] * (length - len(pattern)))
        else:
            pattern = pattern[:length]

    return pattern


def continued_fraction_rhythm(fraction: Union[float, Fraction], length: int) -> List[int]:
    """
    Generate rhythmic pattern from continued fraction expansion.

    Parameters:
    fraction: Number to expand as continued fraction (float or Fraction)
    length (int): Desired pattern length

    Returns:
    List[int]: Pattern based on continued fraction convergents
    """
    if isinstance(fraction, float):
        # Convert float to Fraction for exact arithmetic
        fraction = Fraction(fraction).limit_denominator()

        # Get continued fraction expansion
    cf_expansion = []
    remaining = fraction

    for _ in range(length):
        if remaining.denominator == 0:
            break
        whole = remaining.numerator // remaining.denominator
        cf_expansion.append(whole)
        remaining = Fraction(remaining.denominator,
                             remaining.numerator - whole * remaining.denominator)

        # Create pattern from expansion
    pattern = []
    for i, value in enumerate(cf_expansion):
        # Use modulo 2 to get binary pattern
        pattern.extend([value % 2] * min(value,
                                         3))  # Limit repeats

    # Ensure correct length
    pattern = pattern[:length]
    if len(pattern) < length:
        pattern.extend([0] * (length - len(pattern)))

    return pattern


def modular_rhythm(modulus: int, multiplier: int, length: int, offset: int = 0) -> List[int]:
    """
    Generate rhythmic pattern using modular arithmetic.

    Parameters:
    modulus (int): Modulus for the operation
    multiplier (int): Multiplier for the sequence
    length (int): Length of the pattern
    offset (int): Starting offset

    Returns:
    List[int]: Pattern where 1 indicates positions where (i * multiplier) % modulus == 0

    Example:
    >>> modular_rhythm(7, 3, 14, 0)
    [1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]  # Every 7th step
    """
    pattern = []
    for i in range(length):
        position = (i + offset) % modulus
        if (position * multiplier) % modulus == 0:
            pattern.append(1)
        else:
            pattern.append(0)
    return pattern


def random_walk_rhythm(length: int, p_stay: float = 0.3, p_change: float = 0.7) -> List[int]:
    """
    Generate rhythmic pattern using random walk.

    Parameters:
    length (int): Length of the pattern
    p_stay (float): Probability of staying in current state
    p_change (float): Probability of changing state

    Returns:
    List[int]: Generated pattern
    """
    pattern = [random.choice([0,
                              1])]  # Start with random state

    for _ in range(length - 1):
        rand_val = random.random()
        if rand_val < p_stay:
            # Stay in current state
            pattern.append(pattern[-1])
        elif rand_val < p_stay + p_change:
            # Change state
            pattern.append(1 - pattern[-1])
        else:
            # Random state
            pattern.append(random.choice([0, 1]))

    return pattern


def cellular_automaton_rhythm(rule: int, length: int, width: int = 1) -> List[List[int]]:
    """
    Generate 2D rhythmic pattern using elementary cellular automaton.

    Parameters:
    rule (int): Wolfram rule number (0-255)
    length (int): Number of time steps (rows)
    width (int): Number of cells (columns)

    Returns:
    List[List[int]]: 2D pattern (time × space)
    """
    # Initialize first row with single 1 in middle
    current_row = [0] * width
    if width > 0:
        current_row[width // 2] = 1

    pattern = [current_row.copy()]

    # Precompute rule lookup table
    rule_bits = [(rule >> i) & 1 for i in range(8)]

    for _ in range(length - 1):
        next_row = [0] * width

        for i in range(width):
            # Get neighborhood (with wrap-around)
            left = current_row[(i - 1) % width]
            center = current_row[i]
            right = current_row[(i + 1) % width]

            # Convert neighborhood to rule index
            neighborhood = (left << 2) | (center << 1) | right
            next_row[i] = rule_bits[neighborhood]

        pattern.append(next_row)
        current_row = next_row

    return pattern


def rhythm_to_durations(pattern: List[int], base_duration: float = 1.0) -> List[float]:
    """
    Convert binary pattern to list of durations.

    Parameters:
    pattern (List[int]): Binary pattern (1=note, 0=rest)
    base_duration (float): Duration of each pulse

    Returns:
    List[float]: List of durations for notes and rests
    """
    durations = []
    current_duration = 0.0

    for i, value in enumerate(pattern):
        current_duration += base_duration

        # If we hit a beat or end of pattern, add duration
        if value == 1 or i == len(pattern) - 1:
            durations.append(current_duration)
            current_duration = 0.0

            # Remove trailing zero if present
    if durations and durations[-1] == 0.0:
        durations.pop()

    return durations


def rotate_pattern(pattern: List[int], rotation: int) -> List[int]:
    """
    Rotate a pattern by specified number of steps.

    Parameters:
    pattern (List[int]): Input pattern
    rotation (int): Number of steps to rotate (positive = right, negative = left)

    Returns:
    List[int]: Rotated pattern
    """
    if not pattern:
        return []

    rotation = rotation % len(pattern)
    return pattern[rotation:] + pattern[:rotation]


def invert_pattern(pattern: List[int]) -> List[int]:
    """
    Invert a pattern (swap 0s and 1s).

    Parameters:
    pattern (List[int]): Input pattern

    Returns:
    List[int]: Inverted pattern
    """
    return [1 - x for x in pattern]


def concatenate_patterns(*patterns: List[int]) -> List[int]:
    """
    Concatenate multiple patterns.

    Parameters:
    *patterns: Variable number of patterns to concatenate

    Returns:
    List[int]: Concatenated pattern
    """
    result = []
    for pattern in patterns:
        result.extend(pattern)
    return result


def interleave_patterns(*patterns: List[int]) -> List[int]:
    """
    Interleave multiple patterns.

    Parameters:
    *patterns: Patterns to interleave

    Returns:
    List[int]: Interleaved pattern
    """
    if not patterns:
        return []

    max_len = max(len(p) for p in patterns)
    result = []

    for i in range(max_len):
        for pattern in patterns:
            if i < len(pattern):
                result.append(pattern[i])

    return result


# Example usage and demonstration
def demo():
    """Demonstrate various rhythm generation algorithms."""

    print("=== Rhythmic Pattern Generation Demo ===\n")

    # Euclidean Rhythm
    print("1. Euclidean Rhythm (k=3, n=8):")
    pattern = euclidean_rhythm(3, 8)
    print(f"   Pattern: {pattern}")
    print(f"   As rhythm: {''.join('x' if x else '.' for x in pattern)}")

    # Fibonacci Rhythm
    print("\n2. Fibonacci Rhythm (length=13):")
    pattern = fibonacci_rhythm(13)
    print(f"   Pattern: {pattern}")
    print(f"   As rhythm: {''.join('x' if x else '.' for x in pattern)}")

    # Prime Rhythm
    print("\n3. Prime Number Rhythm (length=16):")
    pattern = prime_rhythm(16)
    print(f"   Pattern: {pattern}")
    print(f"   As rhythm: {''.join('x' if x else '.' for x in pattern)}")

    # L-system Rhythm (Fibonacci)
    print("\n4. L-system Rhythm (Fibonacci L-system):")
    pattern = lindenmayer_rhythm('A', {'A': 'AB', 'B': 'A'}, 4, 13)
    print(f"   Pattern: {pattern}")
    print(f"   As rhythm: {''.join('x' if x else '.' for x in pattern)}")

    # Binary Decomposition
    print("\n5. Binary Decomposition (number=5, length=8):")
    pattern = binary_decomposition_rhythm(5, 8)
    print(f"   Pattern: {pattern}")
    print(f"   As rhythm: {''.join('x' if x else '.' for x in pattern)}")

    # Convert to durations
    print("\n6. Pattern to Durations:")
    durations = rhythm_to_durations(euclidean_rhythm(3, 8), 0.25)
    print(f"   Euclidean (3,8) durations: {durations}")


if __name__ == "__main__":
    demo()


def lcm_multiple():
    return None