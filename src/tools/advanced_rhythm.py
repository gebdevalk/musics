# advanced_rhythm.py
"""
Advanced rhythmic pattern generation algorithms for algorithmic music composition.
Includes techniques from contemporary composers and mathematical music theory.
"""

import math
import random
import itertools
from typing import List, Tuple, Optional, Dict, Any, Union, Callable
from fractions import Fraction
import numpy as np

from rhythm import euclidean_rhythm
from functions import lcm_multiple


# ============================================================================
# 1. Clapping Music Algorithm (Steve Reich)
# ============================================================================

def clapping_music_phases(pattern: List[int], total_phases: Optional[int] = None) -> List[List[int]]:
    """
    Generate phase shifting patterns in the style of Steve Reich's "Clapping Music".

    Parameters:
    pattern (List[int]): Base pattern (binary: 1=clap, 0=rest)
    total_phases (int): Number of phase shifts to generate (default: len(pattern))

    Returns:
    List[List[int]]: List of patterns, each phase shifted by one position

    Example:
    >>> pattern = [1, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0]
    >>> phases = clapping_music_phases(pattern)
    >>> len(phases) == len(pattern)  # Returns True
    """
    if not pattern:
        return []

    if total_phases is None:
        total_phases = len(pattern)

    phases = []
    for i in range(total_phases):
        # Rotate pattern by i positions
        rotated = pattern[i:] + pattern[:i]
        phases.append(rotated)

    return phases


def clapping_music_duet(pattern: List[int], phase: int = 0) -> Tuple[List[int], List[int]]:
    """
    Generate two parts for Clapping Music: one static, one phase shifted.

    Parameters:
    pattern (List[int]): Base pattern
    phase (int): Phase shift amount (0 to len(pattern)-1)

    Returns:
    Tuple[List[int], List[int]]: (static_part, shifted_part)
    """
    if not pattern:
        return [], []

    phase = phase % len(pattern)
    static_part = pattern
    shifted_part = pattern[phase:] + pattern[:phase]

    return static_part, shifted_part


# ============================================================================
# 2. Xenakis Sieve Theory
# ============================================================================

def xenakis_sieve(moduli: List[int], residues: List[List[int]], length: int) -> List[int]:
    """
    Generate rhythmic pattern using Xenakis' sieve theory.
    A sieve is defined by: S = ∪_{i=1}^{n} {k * m_i + r_i}

    Parameters:
    moduli (List[int]): List of moduli (m values)
    residues (List[List[int]]): List of residue lists for each modulus
    length (int): Length of output pattern

    Returns:
    List[int]: Binary pattern where 1 indicates positions in the sieve

    Example:
    >>> xenakis_sieve([3, 4], [[0, 1], [2]], 12)
    # Positions where (n mod 3 ∈ {0,1}) OR (n mod 4 = 2)
    """
    if len(moduli) != len(residues):
        raise ValueError("moduli and residues must have same length")

    pattern = [0] * length

    for i in range(length):
        for m, residue_list in zip(moduli, residues):
            if (i % m) in residue_list:
                pattern[i] = 1
                break  # Position is in the sieve

    return pattern


def sieve_from_intervals(intervals: List[int], length: int) -> List[int]:
    """
    Create a sieve from a list of intervals (simpler interface).

    Parameters:
    intervals (List[int]): List of beat intervals
    length (int): Length of output pattern

    Returns:
    List[int]: Pattern with beats at cumulative sums of intervals
    """
    pattern = [0] * length
    position = 0

    while position < length:
        pattern[position] = 1
        # Move to next interval (cycling through intervals)
        interval_index = position % len(intervals) if intervals else 0
        position += intervals[interval_index] if intervals else 1

    return pattern


# ============================================================================
# 3. Polyrhythm/Polymerer Generators
# ============================================================================

def polyrhythm(layers: List[Tuple[int, int]], length: int) -> List[List[int]]:
    """
    Generate multiple simultaneous rhythmic layers (polyrhythm).

    Parameters:
    layers (List[Tuple[int, int]]): List of (beats, divisions) for each layer
    length (int): Length in smallest common subdivision

    Returns:
    List[List[int]]: List of patterns, one for each layer

    Example:
    >>> polyrhythm([(3, 8), (2, 8)], 24)  # 3 against 2 in 8th notes
    """
    patterns = []

    for beats, divisions in layers:
        # Create Euclidean rhythm for this layer
        pattern = [0] * length

        # Scale to the common length
        scale_factor = length // divisions
        if scale_factor == 0:
            scale_factor = 1

            # Place beats
        for i in range(beats):
            position = (i * divisions * scale_factor) // beats
            if position < length:
                pattern[position] = 1

        patterns.append(pattern)

    return patterns


def polymerer(meters: List[Tuple[int, int]], length: int) -> List[List[int]]:
    """
    Generate multiple simultaneous meters (polymerer).

    Parameters:
    meters (List[Tuple[int, int]]): List of (numerator, denominator) time signatures
    length (int): Length in beats (common denominator)

    Returns:
    List[List[int]]: List of patterns showing strong/weak beats for each meter

    Example:
    >>> polymerer([(3, 4), (4, 4)], 12)  # 3/4 against 4/4
    """
    # Find LCM of denominators for common grid
    denominators = [denom for _, denom in meters]
    common_denom = lcm_multiple(*denominators)

    patterns = []

    for num, denom in meters:
        pattern = [0] * length

        # Scale to common denominator
        beats_per_measure = num * (common_denom // denom)

        for i in range(length):
            beat_in_measure = i % beats_per_measure
            # Strong beat on first beat of measure
            if beat_in_measure == 0:
                pattern[i] = 2  # Strong beat
            # Medium beat on other downbeats (if compound meter)
            elif num > 3 and beat_in_measure % (beats_per_measure // num) == 0:
                pattern[i] = 1  # Medium beat

        patterns.append(pattern)

    return patterns


# ============================================================================
# 4. Metric Modulation (Elliott Carter)
# ============================================================================

def metric_modulation(base_tempo: float, ratio: Fraction,
                      pattern: List[int], subdivisions: int = 4) -> List[float]:
    """
    Apply metric modulation to convert rhythmic pattern to new tempo.

    Parameters:
    base_tempo (float): Starting tempo in BPM
    ratio (Fraction): Tempo ratio (new/old)
    pattern (List[int]): Binary rhythmic pattern
    subdivisions (int): Subdivisions per beat

    Returns:
    List[float]: List of timings in seconds

    Example:
    >>> pattern = [1, 0, 1, 0, 1, 0, 1, 0]  # 8th notes
    >>> metric_modulation(120, Fraction(3, 2), pattern)
    # Converts quarter=120 to dotted-quarter=120 (3:2 ratio)
    """
    # Calculate beat duration in seconds
    beat_duration = 60.0 / base_tempo

    # Apply ratio
    modulated_duration = beat_duration * float(ratio)

    # Convert pattern to timings
    timings = []
    current_time = 0.0

    for i, value in enumerate(pattern):
        if value == 1:
            timings.append(current_time)

            # Move to next subdivision
        current_time += modulated_duration / subdivisions

    return timings


def nested_tuplets(base_pattern: List[int], tuplet_ratio: Fraction,
                   depth: int = 1) -> List[int]:
    """
    Create nested tuplet rhythms.

    Parameters:
    base_pattern (List[int]): Base binary pattern
    tuplet_ratio (Fraction): Tuplet ratio (e.g., 3/2 for triplets)
    depth (int): Nesting depth

    Returns:
    List[int]: Expanded pattern with nested tuplets
    """
    if depth == 0:
        return base_pattern

        # Convert ratio to numerator/denominator
    num = tuplet_ratio.numerator
    den = tuplet_ratio.denominator

    expanded = []

    for value in base_pattern:
        if value == 1:
            # Create tuplet group
            tuplet_group = [1] + [0] * (num - 1)
            expanded.extend(tuplet_group)
        else:
            # Fill with rests
            expanded.extend([0] * den)

            # Recursively apply to deeper levels
    if depth > 1:
        return nested_tuplets(expanded, tuplet_ratio, depth - 1)

    return expanded


# ============================================================================
# 5. Rhythm Necklaces & Bracelets
# ============================================================================

def rhythm_necklace(pattern: List[int]) -> List[List[int]]:
    """
    Generate all rotations of a pattern (the rhythm necklace).

    Parameters:
    pattern (List[int]): Base pattern

    Returns:
    List[List[int]]: All unique rotations (necklace equivalence class)
    """
    if not pattern:
        return []

    n = len(pattern)
    necklaces = []

    # Generate all rotations
    for i in range(n):
        rotated = pattern[i:] + pattern[:i]

        # Check if this rotation is already in our list
        if rotated not in necklaces:
            necklaces.append(rotated)

    return necklaces


def rhythm_bracelet(pattern: List[int]) -> List[List[int]]:
    """
    Generate all rotations and reversals of a pattern (the rhythm bracelet).

    Parameters:
    pattern (List[int]): Base pattern

    Returns:
    List[List[int]]: All unique rotations and reversals (bracelet equivalence class)
    """
    if not pattern:
        return []

    necklaces = rhythm_necklace(pattern)
    bracelets = []

    for necklace in necklaces:
        # Add the necklace
        if necklace not in bracelets:
            bracelets.append(necklace)

            # Add its reversal
        reversed_necklace = list(reversed(necklace))
        if reversed_necklace not in bracelets:
            bracelets.append(reversed_necklace)

    return bracelets


def all_binary_necklaces(n: int, k: Optional[int] = None) -> List[List[int]]:
    """
    Generate all binary necklaces of length n with exactly k ones.

    Parameters:
    n (int): Length of patterns
    k (int): Number of ones (if None, all patterns)

    Returns:
    List[List[int]]: List of unique necklaces
    """
    # Generate all binary patterns
    if k is None:
        patterns = list(itertools.product([0, 1], repeat=n))
    else:
        # Patterns with exactly k ones
        ones_positions = list(itertools.combinations(range(n), k))
        patterns = []
        for positions in ones_positions:
            pattern = [0] * n
            for pos in positions:
                pattern[pos] = 1
            patterns.append(tuple(pattern))

    # Group by necklace equivalence
    necklaces = []
    seen = set()

    for pattern in patterns:
        # Check if this pattern or any rotation is already seen
        is_new = True
        for i in range(n):
            rotated = tuple(pattern[i:] + pattern[:i])
            if rotated in seen:
                is_new = False
                break

        if is_new:
            necklaces.append(list(pattern))
            # Mark all rotations as seen
            for i in range(n):
                rotated = tuple(pattern[i:] + pattern[:i])
                seen.add(rotated)

    return necklaces


# ============================================================================
# 6. Tiling Rhythms (Vuza Canons)
# ============================================================================

def rhythmic_tiling(pattern_a: List[int], pattern_b: List[int],
                    length: int) -> Tuple[List[int], bool]:
    """
    Check if two patterns form a rhythmic tiling (complementary canons).

    Parameters:
    pattern_a (List[int]): First pattern
    pattern_b (List[int]): Second pattern
    length (int): Total length (should be len(a) * len(b) for proper tiling)

    Returns:
    Tuple[List[int], bool]: (combined_pattern, is_tiling)
    """
    combined = [0] * length

    # Place pattern_a at all possible positions
    for i in range(0, length, len(pattern_a)):
        for j, value in enumerate(pattern_a):
            pos = i + j
            if pos < length and value == 1:
                if combined[pos] == 1:
                    return combined, False  # Overlap!
                combined[pos] = 1

                # Place pattern_b at all possible positions
    for i in range(0, length, len(pattern_b)):
        for j, value in enumerate(pattern_b):
            pos = i + j
            if pos < length and value == 1:
                if combined[pos] == 1:
                    return combined, False  # Overlap!
                combined[pos] = 1

                # Check if all positions are filled
    is_tiling = all(v == 1 for v in combined)

    return combined, is_tiling


def generate_vuza_canon(n: int) -> List[Tuple[List[int], List[int]]]:
    """
    Generate Vuza canon pairs for given length n.
    A Vuza canon is a rhythmic tiling where patterns are periodic.

    Parameters:
    n (int): Length of the tiling

    Returns:
    List[Tuple[List[int], List[int]]]: List of (pattern_a, pattern_b) pairs
    """
    # This is a simplified version - full Vuza canon generation is complex
    # We'll generate some candidate pairs based on divisors

    divisors = [d for d in range(1, n) if n % d == 0]
    candidates = []

    for a_len in divisors:
        b_len = n // a_len

        # Generate simple patterns
        pattern_a = [1] + [0] * (
                    a_len - 1)  # Just first beat
        pattern_b = [1] + [0] * (
                    b_len - 1)  # Just first beat

        combined, is_tiling = rhythmic_tiling(pattern_a, pattern_b, n)

        if is_tiling:
            candidates.append((pattern_a, pattern_b))

    return candidates


# ============================================================================
# 7. Stochastics (Xenakis)
# ============================================================================

def stochastic_rhythm(distribution: str, params: Dict[str, float],
                      length: int, density: float = 0.5) -> List[int]:
    """
    Generate rhythmic pattern using stochastic distributions.

    Parameters:
    distribution (str): Type of distribution ('uniform', 'gaussian', 'poisson', 'exponential')
    params (Dict[str, float]): Parameters for the distribution
    length (int): Length of pattern
    density (float): Target density of ones (0.0 to 1.0)

    Returns:
    List[int]: Stochastic binary pattern
    """
    pattern = [0] * length

    if distribution == 'uniform':
        # Simple random placement
        num_ones = int(length * density)
        positions = random.sample(range(length), num_ones)
        for pos in positions:
            pattern[pos] = 1

    elif distribution == 'gaussian':
        # Gaussian distribution around center
        mean = params.get('mean', length / 2)
        std = params.get('std', length / 6)

        # Generate probabilities
        probs = []
        for i in range(length):
            prob = math.exp(-0.5 * ((i - mean) / std) ** 2)
            probs.append(prob)

            # Normalize and select positions
        max_prob = max(probs)
        probs = [p / max_prob for p in probs]

        for i in range(length):
            if random.random() < probs[i] * density:
                pattern[i] = 1

    elif distribution == 'poisson':
        # Poisson process (events in time)
        rate = params.get('rate', density * 10)
        current = 0

        while current < length:
            # Exponential waiting time between events
            wait = random.expovariate(rate)
            current += int(
                wait * length / 10)  # Scale appropriately

            if current < length:
                pattern[current] = 1

    elif distribution == 'exponential':
        # Exponential decay from start
        decay = params.get('decay', 2.0)

        for i in range(length):
            prob = math.exp(-decay * i / length)
            if random.random() < prob * density * 2:
                pattern[i] = 1

    return pattern


def cloud_rhythm(num_events: int, duration: float,
                 time_std: float = 0.1, density_std: float = 0.05) -> List[float]:
    """
    Generate a "cloud" of rhythmic events (Xenakis' granular approach).

    Parameters:
    num_events (int): Number of events in the cloud
    duration (float): Total duration in seconds
    time_std (float): Standard deviation for timing jitter
    density_std (float): Standard deviation for density variations

    Returns:
    List[float]: Event timings in seconds
    """
    timings = []

    # Base evenly spaced events
    base_times = [i * duration / num_events for i in range(num_events)]

    # Add Gaussian jitter
    for base_time in base_times:
        jitter = random.gauss(0, time_std * duration / num_events)
        timing = base_time + jitter
        timing = max(0, min(duration,
                            timing))  # Clamp to duration
        timings.append(timing)

        # Sort timings
    timings.sort()

    return timings


# ============================================================================
# 8. Genetic Algorithm Rhythms
# ============================================================================

class RhythmGenome:
    """Representation of a rhythmic pattern for genetic algorithms."""

    def __init__(self, pattern: List[int], fitness: float = 0.0):
        self.pattern = pattern
        self.fitness = fitness

    def mutate(self, mutation_rate: float = 0.1) -> 'RhythmGenome':
        """Apply random mutations to the pattern."""
        mutated = self.pattern.copy()

        for i in range(len(mutated)):
            if random.random() < mutation_rate:
                mutated[i] = 1 - mutated[
                    i]  # Flip bit

        return RhythmGenome(mutated)

    def crossover(self, other: 'RhythmGenome',
                  crossover_point: Optional[int] = None) -> Tuple['RhythmGenome', 'RhythmGenome']:
        """Perform single-point crossover with another genome."""
        if len(self.pattern) != len(other.pattern):
            raise ValueError("Patterns must have same length for crossover")

        if crossover_point is None:
            crossover_point = random.randint(1, len(self.pattern) - 1)

        child1 = self.pattern[:crossover_point] + other.pattern[crossover_point:]
        child2 = other.pattern[:crossover_point] + self.pattern[crossover_point:]

        return RhythmGenome(child1), RhythmGenome(child2)


def genetic_rhythm(population_size: int, pattern_length: int,
                   generations: int, fitness_func: Callable[[List[int]], float],
                   mutation_rate: float = 0.1,
                   elitism: float = 0.1) -> List[int]:
    """
    Evolve rhythmic patterns using genetic algorithm.

    Parameters:
    population_size (int): Size of population each generation
    pattern_length (int): Length of rhythmic patterns
    generations (int): Number of generations to evolve
    fitness_func (callable): Function that scores a pattern (higher is better)
    mutation_rate (float): Probability of mutation per bit
    elitism (float): Fraction of best individuals to preserve

    Returns:
    List[int]: Best evolved pattern
    """
    # Initialize random population
    population = []
    for _ in range(population_size):
        pattern = [random.choice([0, 1]) for _ in range(pattern_length)]
        genome = RhythmGenome(pattern)
        genome.fitness = fitness_func(pattern)
        population.append(genome)

        # Evolve
    for generation in range(generations):
        # Sort by fitness
        population.sort(key=lambda g: g.fitness, reverse=True)

        # Keep elite individuals
        elite_count = int(population_size * elitism)
        new_population = population[:elite_count]

        # Fill rest with crossover and mutation
        while len(new_population) < population_size:
            # Tournament selection
            tournament_size = 3
            tournament = random.sample(population, tournament_size)
            parent1 = max(tournament, key=lambda g: g.fitness)

            tournament = random.sample(population, tournament_size)
            parent2 = max(tournament, key=lambda g: g.fitness)

            # Crossover
            child1, child2 = parent1.crossover(parent2)

            # Mutate
            child1 = child1.mutate(mutation_rate)
            child2 = child2.mutate(mutation_rate)

            # Evaluate fitness
            child1.fitness = fitness_func(child1.pattern)
            child2.fitness = fitness_func(child2.pattern)

            new_population.extend([child1, child2])

        population = new_population[:population_size]

        # Optional: print progress
        if generation % 10 == 0:
            best_fitness = max(g.fitness for g in population)
            print(f"Generation {generation}: best fitness = {best_fitness:.3f}")

            # Return best pattern
    best_genome = max(population, key=lambda g: g.fitness)
    return best_genome.pattern


# ============================================================================
# 9. Neural Network Rhythms (Simplified)
# ============================================================================

def markov_chain_rhythm(transition_matrix: Dict[Tuple[int, ...], Dict[Tuple[int, ...], float]],
                        initial_state: Tuple[int, ...],
                        length: int) -> List[int]:
    """
    Higher-order Markov chain for rhythm generation.

    Parameters:
    transition_matrix: Dict mapping state tuples to next state probabilities
    initial_state: Starting state tuple
    length: Length of pattern to generate

    Returns:
    List[int]: Generated pattern
    """
    pattern = list(initial_state)
    current_state = initial_state

    while len(pattern) < length:
        # Get possible next states and their probabilities
        if current_state not in transition_matrix:
            # If state not in matrix, choose randomly
            next_state = tuple(random.choice([0, 1]) for _ in range(len(current_state)))
        else:
            transitions = transition_matrix[current_state]

            # Choose next state based on probabilities
            rand_val = random.random()
            cumulative = 0.0

            for next_state, prob in transitions.items():
                cumulative += prob
                if rand_val <= cumulative:
                    break

                    # Add first element of next state to pattern
        pattern.append(next_state[0])

        # Update current state (shift window)
        current_state = next_state

    return pattern[:length]


def simple_rnn_rhythm(seed_pattern: List[int],
                      weights: List[List[float]],
                      iterations: int) -> List[int]:
    """
    Simple RNN-like rhythm generation (deterministic version).

    Parameters:
    seed_pattern: Initial pattern
    weights: Connection weights matrix
    iterations: Number of steps to generate

    Returns:
    List[int]: Generated pattern
    """
    if not weights:
        # Default weights for 3-neuron "network"
        weights = [
            [0.1, 0.8, -0.3],
            [-0.2, 0.5, 0.7],
            [0.6, -0.1, 0.4]
        ]

    pattern = seed_pattern.copy()
    state = [float(x) for x in seed_pattern[:len(weights)]]

    for _ in range(iterations):
        # Update each neuron
        new_state = []
        for i in range(len(weights)):
            # Weighted sum of inputs
            net_input = sum(state[j] * weights[i][j] for j in range(len(state)))

            # Sigmoid activation
            activation = 1.0 / (1.0 + math.exp(-net_input))

            # Threshold to binary
            new_state.append(1 if activation > 0.5 else 0)

            # Update state
        state = new_state

        # Add to pattern (use first neuron's output)
        pattern.append(state[0])

    return pattern


# ============================================================================
# 10. Physical Modeling Rhythms
# ============================================================================

def pendulum_rhythm(initial_angle: float, length: float,
                    gravity: float, duration: float,
                    sample_rate: float = 100.0) -> List[float]:
    """
    Generate rhythm from pendulum motion (simplified physics).

    Parameters:
    initial_angle: Starting angle in radians
    length: Pendulum length in meters
    gravity: Gravity constant (m/s²)
    duration: Simulation duration in seconds
    sample_rate: Samples per second

    Returns:
    List[float]: Timings of pendulum beats (zero-crossings)
    """
    # Simplified pendulum equation: θ'' + (g/L)θ = 0
    # Solution: θ(t) = θ0 * cos(ωt) where ω = sqrt(g/L)

    omega = math.sqrt(gravity / length)
    period = 2 * math.pi / omega

    timings = []
    time = 0.0
    dt = 1.0 / sample_rate

    prev_angle = initial_angle * math.cos(omega * time)

    while time < duration:
        time += dt
        angle = initial_angle * math.cos(omega * time)

        # Detect zero crossing (beat)
        if prev_angle * angle <= 0 and prev_angle >= 0:
            timings.append(time)

        prev_angle = angle

    return timings


def bouncing_ball_rhythm(initial_height: float, restitution: float,
                         gravity: float, duration: float) -> List[float]:
    """
    Generate rhythm from bouncing ball physics.

    Parameters:
    initial_height: Starting height in meters
    restitution: Bounce coefficient (0.0 to 1.0)
    gravity: Gravity constant (m/s²)
    duration: Simulation duration in seconds

    Returns:
    List[float]: Timings of bounces
    """
    timings = [
        0.0]  # Initial drop

    height = initial_height
    time = 0.0
    velocity = 0.0

    while time < duration:
        # Time until next bounce: solve h = v*t - 0.5*g*t² = 0
        # For falling: v = 0, so t = sqrt(2h/g)
        fall_time = math.sqrt(2 * height / gravity) if height > 0 else 0

        time += fall_time
        if time > duration:
            break

        timings.append(time)

        # Update velocity after bounce
        impact_velocity = math.sqrt(2 * gravity * height)
        velocity = impact_velocity * restitution

        # New height from velocity: h = v²/(2g)
        height = (velocity ** 2) / (2 * gravity)

        # If height is very small, stop
        if height < 0.001:
            break

    return timings


def logistic_map_rhythm(r: float, x0: float, length: int,
                        threshold: float = 0.5) -> List[int]:
    """
    Generate rhythm from logistic map (chaotic system).
    x_{n+1} = r * x_n * (1 - x_n)

    Parameters:
    r: Growth rate parameter (3.57-4.0 for chaos)
    x0: Initial value (0.0 to 1.0)
    length: Length of pattern
    threshold: Threshold for binary output

    Returns:
    List[int]: Binary pattern from chaotic sequence
    """
    pattern = []
    x = x0

    for _ in range(length):
        # Logistic map equation
        x = r * x * (1 - x)

        # Convert to binary based on threshold
        pattern.append(1 if x > threshold else 0)

    return pattern


# ============================================================================
# Utility Functions
# ============================================================================

def pattern_to_midi(pattern: List[int], velocity: int = 64,
                    channel: int = 0, note: int = 60) -> List[Dict]:
    """
    Convert binary pattern to MIDI note events.

    Parameters:
    pattern: Binary pattern
    velocity: MIDI velocity (0-127)
    channel: MIDI channel (0-15)
    note: MIDI note number

    Returns:
    List[Dict]: List of MIDI events as dictionaries
    """
    events = []

    for i, value in enumerate(pattern):
        if value > 0:  # Non-zero values trigger notes
            event = {
                'type': 'note_on',
                'time': i * 0.25,
                # Assuming quarter note grid
                'channel': channel,
                'note': note,
                'velocity': velocity
            }
            events.append(event)

    return events


def visualize_patterns(patterns: List[List[int]],
                       labels: Optional[List[str]] = None):
    """
    Simple ASCII visualization of multiple patterns.

    Parameters:
    patterns: List of binary patterns
    labels: Optional list of labels for each pattern
    """
    if labels is None:
        labels = [f"Pattern {i + 1}" for i in range(len(patterns))]

    max_len = max(len(p) for p in patterns)

    print("\n" + "=" * (max_len + 15))
    for label, pattern in zip(labels, patterns):
        # Create visual representation
        visual = ''.join('█' if x > 0 else '░' for x in pattern)

        # Pad if needed
        if len(pattern) < max_len:
            visual += '░' * (max_len - len(pattern))

        print(f"{label:12} {visual}")
    print("=" * (max_len + 15))

"""
11. Spectral Rhythm Decomposition - FFT-based analysis and reconstruction of rhythms 
12. Rhythm from Natural Phenomena - Heartbeat, rainfall, and birdsong patterns 
13. Algorithmic Composition Systems - EMI-style variations and  Oblique Strategies 
14. Microtiming & Groove Algorithms - Swing quantization, humanization, and pocket grooves 
15. Rhythm from Data Sonification - Converting data, stock prices, and text to rhythms                                

The code integrates well with your existing structure and includes:                                                                                                                                                                 

 • Type hints for better code clarity                                                                                                                                                                                               
 • Comprehensive docstrings with examples                                                                                                                                                                                           
 • Utility functions that work with your existing pattern_to_midi() and visualize_patterns()                                                                                                                                        
 • Updated demo function to showcase the new algorithms    
"""


# ============================================================================
# 11. Spectral Rhythm Decomposition
# ============================================================================

def spectral_rhythm_decomposition(pattern: List[int],
                                  window_size: int = 8,
                                  hop_size: int = 4) -> np.ndarray:
    """
    Perform FFT-based spectral analysis of rhythmic patterns.

    Parameters:
    pattern (List[int]): Binary rhythmic pattern
    window_size (int): Size of FFT window
    hop_size (int): Hop size between windows

    Returns:
    np.ndarray: Spectrogram of rhythmic content
    """
    # Convert pattern to numpy array
    signal = np.array(pattern, dtype=np.float32)

    # Pad signal if needed
    if len(signal) < window_size:
        signal = np.pad(signal, (0, window_size - len(signal)))

        # Calculate number of windows
    num_windows = max(1, (len(signal) - window_size) // hop_size + 1)

    # Initialize spectrogram
    spectrogram = np.zeros((window_size // 2 + 1, num_windows))

    # Compute FFT for each window
    for i in range(num_windows):
        start = i * hop_size
        end = start + window_size

        if end > len(signal):
            window = np.pad(signal[start:], (0, end - len(signal)))
        else:
            window = signal[start:end]

            # Apply window function (Hamming)
        window = window * np.hamming(window_size)

        # Compute FFT
        fft_result = np.fft.rfft(window)
        spectrogram[:, i] = np.abs(fft_result)

    return spectrogram


def rhythm_from_spectrum(spectrogram: np.ndarray,
                         threshold: float = 0.5) -> List[int]:
    """
    Reconstruct rhythmic pattern from spectrogram.

    Parameters:
    spectrogram (np.ndarray): Spectral representation
    threshold (float): Threshold for binary conversion

    Returns:
    List[int]: Reconstructed binary pattern
    """
    # Inverse FFT for each window
    window_size = (spectrogram.shape[0] - 1) * 2
    hop_size = 1  # Default hop size for reconstruction

    # Initialize output signal
    output_len = spectrogram.shape[1] * hop_size + window_size - hop_size
    reconstructed = np.zeros(output_len)
    window_sum = np.zeros(output_len)

    # Reconstruct from each spectral frame
    for i in range(spectrogram.shape[1]):
        # Get magnitude spectrum
        mag_spectrum = spectrogram[:, i]

        # Create random phase for reconstruction
        phase = np.random.uniform(0, 2 * np.pi, len(mag_spectrum))
        complex_spectrum = mag_spectrum * np.exp(1j * phase)

        # Inverse FFT
        window_signal = np.fft.irfft(complex_spectrum)

        # Apply overlap-add
        start = i * hop_size
        end = start + len(window_signal)
        reconstructed[start:end] += window_signal
        window_sum[start:end] += 1

        # Average overlapping windows
    window_sum[
        window_sum == 0] = 1  # Avoid division by zero
    reconstructed = reconstructed / window_sum

    # Convert to binary pattern
    pattern = [1 if x > threshold else 0 for x in reconstructed]

    return pattern


# ============================================================================
# 12. Rhythm from Natural Phenomena
# ============================================================================

def heartbeat_rhythm(bpm: float = 72,
                     variability: float = 0.1,
                     duration: float = 10.0) -> List[float]:
    """
    Generate rhythm based on human heartbeat patterns.

    Parameters:
    bpm (float): Beats per minute (resting heart rate)
    variability (float): Heart rate variability (0.0 to 1.0)
    duration (float): Duration in seconds

    Returns:
    List[float]: Timings of heartbeats
    """
    timings = []
    current_time = 0.0

    # Base inter-beat interval
    base_interval = 60.0 / bpm

    while current_time < duration:
        timings.append(current_time)

        # Add variability (respiratory sinus arrhythmia)
        variability_factor = 1.0 + random.uniform(-variability, variability)

        # Slight acceleration/deceleration pattern
        respiratory_cycle = math.sin(current_time * 0.2) * 0.05

        current_time += base_interval * variability_factor * (1 + respiratory_cycle)

    return timings


def rainfall_rhythm(intensity: float = 0.5,
                    duration: float = 10.0,
                    sample_rate: float = 100.0) -> List[float]:
    """
    Generate rhythm based on rainfall patterns.

    Parameters:
    intensity (float): Rainfall intensity (0.0 to 1.0)
    duration (float): Duration in seconds
    sample_rate (float): Samples per second

    Returns:
    List[float]: Timings of raindrop impacts
    """
    timings = []

    # Raindrop rate based on intensity
    drop_rate = intensity * 20.0  # drops per second at max intensity

    # Simulate Poisson process for raindrops
    time = 0.0
    while time < duration:
        # Exponential waiting time between drops
        wait_time = random.expovariate(drop_rate)
        time += wait_time

        if time < duration:
            timings.append(time)

            # Add occasional clusters (rain bursts)
    if intensity > 0.7:
        # Add a burst of rapid drops
        burst_time = random.uniform(duration * 0.3, duration * 0.7)
        burst_drops = random.randint(3, 8)
        burst_interval = 0.05  # Very close drops

        for i in range(burst_drops):
            drop_time = burst_time + i * burst_interval
            if drop_time < duration:
                timings.append(drop_time)

    timings.sort()
    return timings


def bird_song_rhythm(species: str = "sparrow",
                     duration: float = 5.0) -> List[float]:
    """
    Generate rhythm based on bird song patterns.

    Parameters:
    species (str): Bird species type
    duration (float): Duration in seconds

    Returns:
    List[float]: Timings of song elements
    """
    # Species-specific patterns
    patterns = {
        "sparrow": {
            "syllables_per_phrase": [3, 4, 3],
            "syllable_duration": 0.1,
            "gap_duration": 0.05,
            "phrase_gap": 0.3
        },
        "robin": {
            "syllables_per_phrase": [2, 2, 3, 2],
            "syllable_duration": 0.15,
            "gap_duration": 0.08,
            "phrase_gap": 0.5
        },
        "blackbird": {
            "syllables_per_phrase": [4, 3, 5, 4],
            "syllable_duration": 0.08,
            "gap_duration": 0.03,
            "phrase_gap": 0.4
        },
        "woodpecker": {
            "syllables_per_phrase": [8, 6, 10],
            "syllable_duration": 0.05,
            "gap_duration": 0.02,
            "phrase_gap": 1.0
        }
    }

    params = patterns.get(species, patterns["sparrow"])

    timings = []
    current_time = 0.0

    while current_time < duration:
        for phrase_length in params["syllables_per_phrase"]:
            for _ in range(phrase_length):
                timings.append(current_time)
                current_time += params["syllable_duration"]
                current_time += params["gap_duration"]

            current_time += params["phrase_gap"]

            if current_time >= duration:
                break

    return timings


# ============================================================================
# 13. Algorithmic Composition Systems
# ============================================================================

def emi_style_variation(pattern: List[int],
                        similarity: float = 0.7) -> List[int]:
    """
    Generate variations in the style of David Cope's EMI.

    Parameters:
    pattern (List[int]): Original pattern
    similarity (float): How similar to stay to original (0.0 to 1.0)

    Returns:
    List[int]: Varied pattern
    """
    if not pattern:
        return []

    varied = pattern.copy()

    # Apply different variation techniques based on similarity
    for i in range(len(varied)):
        if random.random() > similarity:
            # Mutation operations
            operation = random.choice(["flip", "swap", "insert", "delete"])

            if operation == "flip":
                varied[i] = 1 - varied[i]

            elif operation == "swap" and i < len(varied) - 1:
                varied[i], varied[i + 1] = varied[i + 1], varied[i]

            elif operation == "insert" and random.random() < 0.3:
                # Insert a beat
                varied.insert(i, random.choice([0, 1]))

            elif operation == "delete" and len(varied) > 1:
                # Delete a beat
                varied.pop(i)

                # Ensure pattern has reasonable length
    if len(varied) > len(pattern) * 2:
        varied = varied[:len(pattern) * 2]
    elif len(varied) < len(pattern) // 2:
        varied = varied + [0] * (len(pattern) // 2 - len(varied))

    return varied


def oblique_strategies_transform(pattern: List[int],
                                 strategy: str = "random") -> List[int]:
    """
    Apply Brian Eno's Oblique Strategies to transform rhythms.

    Parameters:
    pattern (List[int]): Original pattern
    strategy (str): Transformation strategy

    Returns:
    List[int]: Transformed pattern
    """
    strategies = {
        "reverse": lambda p: list(reversed(p)),
        "invert": lambda p: [1 - x for x in p],
        "slowest": lambda p: [x for x in p for _ in range(3)],
        "fastest": lambda p: p[::2] if len(p) > 1 else p,
        "disconnect": lambda p: [p[i] if i % 2 == 0 else 0 for i in range(len(p))],
        "only_essentials": lambda p: [1 if x == 1 and i % 2 == 0 else 0 for i, x in enumerate(p)],
        "mistakes": lambda p: [p[i] if random.random() > 0.2 else 1 - p[i] for i in range(len(p))],
        "silence": lambda p: [0] * len(p),
        "double": lambda p: p + p,
        "mirror": lambda p: p + list(reversed(p)),
    }

    if strategy == "random":
        strategy = random.choice(list(strategies.keys()))

    transform_func = strategies.get(strategy, lambda p: p)
    return transform_func(pattern)


# ============================================================================
# 14. Microtiming & Groove Algorithms
# ============================================================================

def swing_quantization(pattern: List[int],
                       swing_ratio: float = 0.6,
                       subdivision: int = 2) -> List[float]:
    """
    Apply swing feel to a rhythmic pattern.

    Parameters:
    pattern (List[int]): Binary pattern
    swing_ratio (float): Ratio of long to short (0.5=straight, 0.67=typical swing)
    subdivision (int): Subdivisions per beat (usually 2 for eighth-note swing)

    Returns:
    List[float]: Swung timings
    """
    timings = []
    beat_duration = 1.0 / subdivision

    for i, value in enumerate(pattern):
        if value == 1:
            beat_position = i % subdivision

            if subdivision == 2:
                # Eighth-note swing
                if beat_position == 0:
                    # Downbeat - on the grid
                    timing = i * beat_duration
                else:
                    # Upbeat - delayed
                    timing = i * beat_duration * swing_ratio
            else:
                # General swing for other subdivisions
                if beat_position % 2 == 0:
                    timing = i * beat_duration
                else:
                    timing = i * beat_duration * swing_ratio

            timings.append(timing)

    return timings


def humanize_rhythm(timings: List[float],
                    timing_variance: float = 0.02,
                    velocity_variance: float = 0.1) -> List[Dict]:
    """
    Add human-like imperfections to rhythmic timings.

    Parameters:
    timings (List[float]): Original timings
    timing_variance (float): Maximum timing deviation in seconds
    velocity_variance (float): Maximum velocity deviation (0.0 to 1.0)

    Returns:
    List[Dict]: Humanized events with timing and velocity
    """
    humanized = []

    for i, timing in enumerate(timings):
        # Add timing jitter (slightly more on upbeats)
        is_upbeat = (i % 2 == 1)
        jitter_factor = 1.2 if is_upbeat else 1.0
        jitter = random.uniform(-timing_variance, timing_variance) * jitter_factor

        # Add velocity variation (slightly softer on upbeats)
        base_velocity = 0.7
        if is_upbeat:
            base_velocity = 0.6

        velocity_jitter = random.uniform(-velocity_variance, velocity_variance)
        velocity = max(0.1, min(1.0, base_velocity + velocity_jitter))

        humanized.append({
            'time': timing + jitter,
            'velocity': velocity,
            'original_time': timing
        })

        # Sort by time after adding jitter
    humanized.sort(key=lambda x: x['time'])

    return humanized


def pocket_groove(base_pattern: List[int],
                  pocket_depth: float = 0.05,
                  accent_pattern: Optional[List[int]] = None) -> List[Dict]:
    """
    Create a "pocket" groove with laid-back feel.

    Parameters:
    base_pattern (List[int]): Binary rhythm pattern
    pocket_depth (float): How laid back (0.0 to 0.1 seconds)
    accent_pattern (List[int]): Optional accent pattern (higher values = stronger accents)

    Returns:
    List[Dict]: Groove events with timing and accent
    """
    if accent_pattern is None:
        # Default: accent every 4th beat
        accent_pattern = [1 if i % 4 == 0 else 0.5 for i in range(len(base_pattern))]

    groove = []
    current_time = 0.0
    beat_duration = 0.25  # Assuming 16th notes at 120 BPM

    for i, value in enumerate(base_pattern):
        if value == 1:
            # Apply pocket (delay later beats more)
            beat_in_bar = i % 4
            pocket_factor = 1.0 + (
                        beat_in_bar * 0.2)  # Later beats get more delay

            timing = current_time + pocket_depth * pocket_factor
            accent = accent_pattern[i] if i < len(accent_pattern) else 0.5

            groove.append({
                'time': timing,
                'accent': accent,
                'beat_position': beat_in_bar
            })

        current_time += beat_duration

    return groove


# ============================================================================
# 15. Rhythm from Data Sonification
# ============================================================================

def data_to_rhythm(data: List[float],
                   threshold_type: str = "median",
                   smoothing: int = 1) -> List[int]:
    """
    Convert any numerical data sequence to rhythmic pattern.

    Parameters:
    data (List[float]): Input data sequence
    threshold_type (str): "median", "mean", or "adaptive"
    smoothing (int): Moving average window size

    Returns:
    List[int]: Binary rhythmic pattern
    """
    if not data:
        return []

        # Smooth data if requested
    if smoothing > 1:
        smoothed = []
        for i in range(len(data)):
            start = max(0, i - smoothing // 2)
            end = min(len(data), i + smoothing // 2 + 1)
            window = data[start:end]
            smoothed.append(sum(window) / len(window))
        data = smoothed

        # Calculate threshold
    if threshold_type == "median":
        threshold = np.median(data)
    elif threshold_type == "mean":
        threshold = np.mean(data)
    elif threshold_type == "adaptive":
        # Use local thresholds
        pattern = []
        window_size = 5
        for i in range(len(data)):
            start = max(0, i - window_size // 2)
            end = min(len(data), i + window_size // 2 + 1)
            local_threshold = np.mean(data[start:end])
            pattern.append(1 if data[i] > local_threshold else 0)
        return pattern
    else:
        threshold = np.mean(data)

        # Convert to binary pattern
    pattern = [1 if x > threshold else 0 for x in data]

    return pattern


def stock_market_rhythm(prices: List[float],
                        lookback: int = 5) -> List[int]:
    """
    Generate rhythm from stock price movements.

    Parameters:
    prices (List[float]): Historical price data
    lookback (int): Number of periods for trend calculation

    Returns:
    List[int]: Rhythm based on price trends
    """
    if len(prices) < lookback + 1:
        return [0] * len(prices)

    pattern = [0] * len(prices)

    for i in range(lookback, len(prices)):
        # Calculate simple moving average
        sma = sum(prices[i - lookback:i]) / lookback

        # Current price relative to SMA
        if prices[
            i] > sma * 1.02:  # 2% above SMA
            pattern[
                i] = 2  # Strong beat
        elif prices[i] > sma:
            pattern[
                i] = 1  # Weak beat
        elif prices[
            i] < sma * 0.98:  # 2% below SMA
            pattern[
                i] = -1  # Rest/silence marker
        # else: pattern[i] = 0 (no beat)

    return pattern


def text_to_rhythm(text: str,
                   mode: str = "syllables") -> List[int]:
    """
    Convert text to rhythmic pattern based on linguistic features.

    Parameters:
    text (str): Input text
    mode (str): "syllables", "words", or "stress"

    Returns:
    List[int]: Rhythmic pattern
    """

    # Simple syllable counter (approximate)
    def count_syllables(word):
        word = word.lower()
        count = 0
        vowels = "aeiouy"
        if word[0] in vowels:
            count += 1
        for i in range(1, len(word)):
            if word[i] in vowels and word[i - 1] not in vowels:
                count += 1
        if word.endswith("e"):
            count -= 1
        if count == 0:
            count = 1
        return count

    words = text.split()
    pattern = []

    if mode == "syllables":
        for word in words:
            syllables = count_syllables(word)
            # First syllable gets a beat, others get subdivisions
            pattern.append(1)
            pattern.extend([0] * (syllables - 1))

    elif mode == "words":
        for word in words:
            # Each word gets a beat, longer words get stronger beats
            word_len = len(word)
            if word_len > 7:
                pattern.append(
                    2)  # Strong beat for long words
            elif word_len > 4:
                pattern.append(
                    1)  # Medium beat
            else:
                pattern.append(
                    1)  # Weak beat

    elif mode == "stress":
        # Simple stress pattern (stressed syllables get beats)
        for word in words:
            syllables = count_syllables(word)
            if syllables == 1:
                pattern.append(1)
            else:
                # Alternate stressed/unstressed
                for s in range(syllables):
                    pattern.append(1 if s % 2 == 0 else 0)

                    # Add rests between sentences/phrases
    if "." in text or "!" in text or "?" in text:
        # Add a rest at the end
        pattern.append(0)

    return pattern

"""
16. Constraint Programming Rhythms - All-interval rhythms, isorhythms, and constraint satisfaction 
17. Fractal Rhythms - Cantor set, dragon curve, and L-system based rhythms 
18. Rhythm from Geometry - Polygon rotations, circle  
of fifths in time, golden ratio rhythms 
19. Indian Tala System Algorithms - Complete Tala class with matra/vibhag structure, theka patterns, and konnakol 
20. West African Timeline Algorithms - Bell patterns, cross-rhythms (3:2 hemiola), African polyrhythms, and djembe patterns 
"""


# ============================================================================
# 16. Constraint Programming Rhythms
# ============================================================================

def all_interval_rhythm(length: int = 12) -> List[int]:
    """
    Generate all-interval rhythm where intervals between beats are all different.
    This creates a rhythm where the time distances between consecutive beats are unique.

    Parameters:
    length (int): Length of the rhythm pattern

    Returns:
    List[int]: Binary pattern with unique intervals between beats
    """
    # This is a simplified version - full all-interval series are complex
    # We'll generate a pattern with approximately unique intervals

    pattern = [0] * length

    # Start with first beat
    pattern[0] = 1

    # Generate intervals that are roughly unique
    used_intervals = set()
    current_pos = 0

    while current_pos < length - 1:
        # Try to find an interval not used yet
        possible_intervals = [i for i in range(1, length // 2 + 1)
                              if i not in used_intervals and current_pos + i < length]

        if not possible_intervals:
            # If no unique interval fits, use smallest available
            possible_intervals = [i for i in range(1, length // 2 + 1)
                                  if current_pos + i < length]

        if possible_intervals:
            interval = random.choice(possible_intervals)
            used_intervals.add(interval)
            current_pos += interval

            if current_pos < length:
                pattern[current_pos] = 1
        else:
            break

    return pattern


def isorhythm(talea: List[int], color: List[int], repetitions: int = 3) -> List[int]:
    """
    Generate isorhythmic pattern (medieval/renaissance technique).
    Talea = rhythmic pattern, Color = pitch pattern (simplified to binary here).

    Parameters:
    talea (List[int]): Rhythmic pattern (binary)
    color (List[int]): "Pitch" pattern (binary, determines beat strength)
    repetitions (int): Number of repetitions

    Returns:
    List[int]: Isorhythmic pattern with varying beat strengths
    """
    result = []

    for rep in range(repetitions):
        for i, beat in enumerate(talea):
            if beat == 1:
                # Use color pattern cyclically to determine beat strength
                color_idx = (rep * len(talea) + i) % len(color)
                result.append(color[
                                  color_idx] + 1)  # +1 to make 1 or 2
            else:
                result.append(0)

    return result


def constraint_satisfaction_rhythm(constraints: List[Callable[[List[int]], bool]],
                                   length: int = 8,
                                   max_attempts: int = 1000) -> Optional[List[int]]:
    """
    Generate rhythm that satisfies multiple constraints using backtracking.

    Parameters:
    constraints (List[callable]): List of constraint functions
    length (int): Length of pattern
    max_attempts (int): Maximum attempts before giving up

    Returns:
    Optional[List[int]]: Pattern satisfying all constraints, or None
    """

    def backtrack(pattern, pos):
        if pos == length:
            # Check all constraints
            for constraint in constraints:
                if not constraint(pattern):
                    return None
            return pattern

            # Try both 0 and 1
        for value in [0, 1]:
            pattern[pos] = value
            result = backtrack(pattern.copy(), pos + 1)
            if result is not None:
                return result

        return None

        # Try multiple times with different random seeds

    for attempt in range(max_attempts):
        # Start with random pattern
        initial = [random.choice([0, 1]) for _ in range(length)]
        result = backtrack(initial, 0)
        if result is not None:
            return result

    return None


# ============================================================================
# 17. Fractal Rhythms
# ============================================================================

def cantor_set_rhythm(iterations: int = 3, length: int = 27) -> List[int]:
    """
    Generate rhythm based on the Cantor set (fractal).
    Start with full beat, recursively remove middle third.

    Parameters:
    iterations (int): Number of fractal iterations
    length (int): Total length (should be 3^iterations for perfect fractal)

    Returns:
    List[int]: Fractal rhythm pattern
    """
    # Initialize with all beats
    pattern = [1] * length

    def remove_middle(start, end, level):
        if level >= iterations or end - start < 3:
            return

            # Calculate thirds
        third = (end - start) // 3

        # Remove middle third
        for i in range(start + third, start + 2 * third):
            if i < len(pattern):
                pattern[i] = 0

                # Recurse on remaining thirds
        remove_middle(start, start + third, level + 1)
        remove_middle(start + 2 * third, end, level + 1)

    remove_middle(0, length, 0)
    return pattern


def dragon_curve_rhythm(iterations: int = 4) -> List[int]:
    """
    Generate rhythm based on dragon curve folding.
    Each iteration adds a turn (1=beat, 0=rest) based on folding pattern.

    Parameters:
    iterations (int): Number of folding iterations

    Returns:
    List[int]: Dragon curve rhythm
    """
    # Start with single beat
    pattern = [1]

    for _ in range(iterations):
        # Copy pattern, reverse it, and append with alternating 1/0
        copy = pattern.copy()
        pattern.append(
            1)  # Always add a beat at the fold

        # Append reversed copy with flipped bits
        for i in range(len(copy) - 1, -1, -1):
            pattern.append(1 - copy[
                i])  # Flip 0<->1

    return pattern


def l_system_rhythm(axiom: str, rules: Dict[str, str],
                    iterations: int = 3) -> List[int]:
    """
    Generate fractal rhythm using L-systems (Lindenmayer systems).

    Parameters:
    axiom (str): Starting string
    rules (Dict[str, str]): Replacement rules
    iterations (int): Number of iterations

    Returns:
    List[int]: L-system generated rhythm (A=1, B=0, etc.)
    """
    current = axiom

    for _ in range(iterations):
        next_str = ""
        for char in current:
            next_str += rules.get(char, char)
        current = next_str

        # Convert to binary pattern
    pattern = []
    for char in current:
        if char == 'A':
            pattern.append(1)
        elif char == 'B':
            pattern.append(0)
        elif char == 'C':
            pattern.append(
                2)  # Strong beat
        else:
            pattern.append(
                0)  # Default to rest

    return pattern


# ============================================================================
# 18. Rhythm from Geometry
# ============================================================================

def polygon_rotation_rhythm(sides: int = 5,
                            rotations: int = 8,
                            offset: float = 0.0) -> List[int]:
    """
    Generate rhythm by rotating a polygon and marking vertices.

    Parameters:
    sides (int): Number of polygon sides
    rotations (int): Number of rotation steps
    offset (float): Initial rotation offset (0.0 to 1.0)

    Returns:
    List[int]: Rhythm pattern from polygon vertices
    """
    pattern = [0] * rotations

    # Calculate vertex angles
    for step in range(rotations):
        angle = (step / rotations + offset) * 2 * math.pi

        # Check if angle aligns with a vertex
        vertex_angle = (angle * sides) / (2 * math.pi)
        distance_to_vertex = abs(vertex_angle - round(vertex_angle))

        # If close to a vertex, add a beat
        if distance_to_vertex < 0.1:
            pattern[step] = 1

    return pattern


def circle_of_fifths_rhythm(notes: int = 12,
                            pattern_length: int = 24) -> List[int]:
    """
    Generate rhythm based on circle of fifths progression in time.

    Parameters:
    notes (int): Number of notes in circle (usually 12)
    pattern_length (int): Length of rhythm pattern

    Returns:
    List[int]: Rhythm with beats at circle of fifths positions
    """
    pattern = [0] * pattern_length

    # Circle of fifths: C, G, D, A, E, B, F#, C#, G#, D#, A#, F
    # Map to positions in pattern
    current = 0
    for i in range(pattern_length):
        if i % (pattern_length // notes) == 0:
            pattern[current % pattern_length] = 1
            current += 7  # Fifth = 7 semitones
            current %= notes

    return pattern


def golden_ratio_rhythm(length: int = 34,
                        phi: float = 1.61803398875) -> List[int]:
    """
    Generate rhythm based on golden ratio spacing.

    Parameters:
    length (int): Length of pattern
    phi (float): Golden ratio value

    Returns:
    List[int]: Rhythm with beats at golden ratio intervals
    """
    pattern = [0] * length

    # Place beats at positions approximating golden ratio
    position = 0.0
    beat_count = 0

    while int(position) < length:
        idx = int(position)
        if idx < length:
            pattern[idx] = 1
            beat_count += 1

            # Move by golden ratio
        position += phi * (length / (beat_count + 1))

    return pattern


# ============================================================================
# 19. Indian Tala System Algorithms
# ============================================================================

class Tala:
    """Representation of an Indian Tala (rhythmic cycle)."""

    # Common talas and their structure
    COMMON_TALAS = {
        "teental": {
            "matras": 16,
            "vibhags": [4, 4, 4, 4],
            "tali_khali": ["T", " ", "T", " "]
            # T = clap, _ = wave, space = nothing
        },
        "jhaptal": {
            "matras": 10,
            "vibhags": [2, 3, 2, 3],
            "tali_khali": ["T", " ", "T", " "]
        },
        "rupak": {
            "matras": 7,
            "vibhags": [3, 2, 2],
            "tali_khali": [" ", "T", "T"]
            # Starts with khali
        },
        "ektal": {
            "matras": 12,
            "vibhags": [2, 2, 2, 2, 2, 2],
            "tali_khali": ["T", " ", "T", " ", "T", " "]
        }
    }

    def __init__(self, name: str = "teental"):
        self.name = name
        self.structure = self.COMMON_TALAS.get(name, self.COMMON_TALAS["teental"])

    def generate_pattern(self, laya: float = 1.0) -> List[Dict]:
        """
        Generate tala pattern with matra positions and accents.

        Parameters:
        laya (float): Tempo multiplier (1.0 = normal, 2.0 = double speed)

        Returns:
        List[Dict]: List of matra events with timing and accent
        """
        matras = self.structure["matras"]
        vibhags = self.structure["vibhags"]
        tali_khali = self.structure["tali_khali"]

        pattern = []
        matra_idx = 0
        vibhag_idx = 0

        for i, vibhag_length in enumerate(vibhags):
            tali_type = tali_khali[i] if i < len(tali_khali) else " "

            for j in range(vibhag_length):
                # Determine accent based on position in vibhag
                if j == 0:
                    # First matra of vibhag gets special treatment
                    if tali_type == "T":
                        accent = 2  # Strong clap (tali)
                    elif tali_type == " ":
                        accent = 1  # Medium (khali or normal)
                    else:
                        accent = 0  # Wave or other
                else:
                    accent = 0  # Normal matra

                # Sam (first beat of cycle) gets strongest accent
                if matra_idx == 0:
                    accent = 3

                pattern.append({
                    "matra": matra_idx + 1,
                    "vibhag": i + 1,
                    "accent": accent,
                    "time": matra_idx / laya,
                    "tali_khali": tali_type
                })

                matra_idx += 1

        return pattern

    def theka_pattern(self, instrument: str = "tabla") -> List[int]:
        """
        Generate theka (basic stroke pattern) for the tala.

        Parameters:
        instrument (str): "tabla" or "mridangam"

        Returns:
        List[int]: Simplified binary pattern
        """
        matras = self.structure["matras"]

        if instrument == "tabla":
            # Simplified tabla bols to binary
            if self.name == "teental":
                # Teental theka: Dha Dhin Dhin Dha | Dha Dhin Dhin Dha | Dha Tin Tin Ta | Ta Dhin Dhin Dha
                pattern = [1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1]
            elif self.name == "jhaptal":
                # Jhaptal: Dhi Na | Dhi Dhi Na | Ti Na | Dhi Dhi Na
                pattern = [1, 0, 1, 1, 0, 1, 0, 1, 1, 0]
            else:
                # Default: accent first beat of each vibhag
                pattern = [0] * matras
                pos = 0
                for vibhag in self.structure["vibhags"]:
                    pattern[pos] = 1
                    pos += vibhag
        else:
            # Mridangam or other
            pattern = [1 if i % 2 == 0 else 0 for i in range(matras)]

        return pattern


def konnakol_pattern(syllables: List[str] = None,
                     pattern: List[int] = None) -> List[str]:
    """
    Generate konnakol (South Indian vocal percussion) pattern.

    Parameters:
    syllables (List[str]): List of konnakol syllables
    pattern (List[int]): Rhythm pattern to apply

    Returns:
    List[str]: Konnakol syllable sequence
    """
    if syllables is None:
        syllables = ["Ta", "Ka", "Di", "Mi", "Tom", "Nam"]

    if pattern is None:
        pattern = [1, 0, 1, 0, 1, 1, 0, 1]

    konnakol = []
    syllable_idx = 0

    for beat in pattern:
        if beat > 0:
            konnakol.append(syllables[syllable_idx % len(syllables)])
            syllable_idx += 1
        else:
            konnakol.append(
                "-")  # Rest

    return konnakol


# ============================================================================
# 20. West African Timeline Algorithms
# ============================================================================

def bell_pattern(meter: Tuple[int, int] = (12, 8),
                 pattern_name: str = "standard") -> List[int]:
    """
    Generate West African bell pattern (timeline).

    Parameters:
    meter (Tuple[int, int]): Time signature (pulses, subdivision)
    pattern_name (str): Name of specific pattern

    Returns:
    List[int]: Bell pattern with accents
    """
    pulses, subdivision = meter

    patterns = {
        "standard": [1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0],
        # 12/8 standard
        "clave": [1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0],
        # 3-2 son clave
        "bossanova": [1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0],
        # Bossanova
        "funk": [1, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0],
        # Funk
        "ghanian": [1, 0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 0],
        # Ghanian
    }

    if pattern_name in patterns:
        base_pattern = patterns[pattern_name]
    else:
        # Generate simple pattern based on meter
        base_pattern = [1 if i % (subdivision // 2) == 0 else 0
                        for i in range(pulses)]

        # Ensure pattern length matches pulses
    if len(base_pattern) > pulses:
        base_pattern = base_pattern[:pulses]
    elif len(base_pattern) < pulses:
        base_pattern = base_pattern * (pulses // len(base_pattern) + 1)
        base_pattern = base_pattern[:pulses]

    return base_pattern


def cross_rhythm_3_2(length: int = 12) -> List[List[int]]:
    """
    Generate 3:2 cross-rhythm (hemiola) patterns.

    Parameters:
    length (int): Length of pattern (should be multiple of 6)

    Returns:
    List[List[int]]: Two layers showing 3 against 2
    """
    # Layer 1: triple meter (3 beats)
    layer1 = [1 if i % (length // 3) == 0 else 0 for i in range(length)]

    # Layer 2: duple meter (2 beats)
    layer2 = [1 if i % (length // 2) == 0 else 0 for i in range(length)]

    return [layer1, layer2]


def african_polyrhythm(layers: int = 3,
                       base_length: int = 12) -> List[List[int]]:
    """
    Generate complex African polyrhythm with multiple interlocking parts.

    Parameters:
    layers (int): Number of rhythmic layers
    base_length (int): Base length for patterns

    Returns:
    List[List[int]]: List of interlocking rhythmic patterns
    """
    all_patterns = []

    # Common African polyrhythm ratios
    ratios = [(3, 2), (4, 3), (5, 4), (7, 4)]

    for i in range(layers):
        if i < len(ratios):
            num, den = ratios[i]
        else:
            # Generate random ratio
            num = random.randint(2, 7)
            den = random.randint(2, 7)

            # Create pattern based on ratio
        pattern = [0] * base_length
        step = base_length // num

        for j in range(num):
            pos = (j * step) % base_length
            pattern[pos] = 1

            # Add secondary beats for complexity
        for j in range(1, num):
            if random.random() > 0.5:
                pos = (j * step + step // 2) % base_length
                pattern[pos] = 1

        all_patterns.append(pattern)

    return all_patterns


def djembe_pattern(technique: str = "basic",
                   length: int = 8) -> List[Dict]:
    """
    Generate djembe drum pattern with different stroke techniques.

    Parameters:
    technique (str): "basic", "solo", or "accompaniment"
    length (int): Pattern length

    Returns:
    List[Dict]: Djembe events with stroke type and timing
    """
    pattern = []

    if technique == "basic":
        # Basic djembe pattern: bass (B), tone (T), slap (S)
        strokes = ["B", "T", "S", "T", "B", "T", "S", "T"]
        for i, stroke in enumerate(strokes):
            pattern.append({
                "time": i * 0.5,
                "stroke": stroke,
                "accent": 1 if i % 2 == 0 else 0
            })

    elif technique == "solo":
        # Solo pattern with more complexity
        for i in range(length):
            stroke = random.choice(["B", "T", "S"])
            accent = 2 if i % 4 == 0 else (1 if i % 2 == 0 else 0)
            pattern.append({
                "time": i * 0.25,
                "stroke": stroke,
                "accent": accent
            })

    else:  # accompaniment
        # Steady accompaniment pattern
        for i in range(length):
            if i % 2 == 0:
                pattern.append({
                    "time": i * 0.5,
                    "stroke": "B",
                    "accent": 0
                })
            else:
                pattern.append({
                    "time": i * 0.5,
                    "stroke": "T",
                    "accent": 0
                })

    return pattern


# ============================================================================
# Update Demo Function
# ============================================================================

def demo_advanced_rhythms():
    """Demonstrate advanced rhythm generation algorithms."""

    print("=== Advanced Rhythmic Pattern Generation Demo ===\n")

    # ... (existing demo code for algorithms 1-15) ...

    # 16. Constraint Programming
    print("\n16. Constraint Programming - All-Interval Rhythm:")
    interval_rhythm = all_interval_rhythm(12)
    print(f"   All-interval pattern: {interval_rhythm}")

    # 17. Fractal Rhythms
    print("\n17. Fractal Rhythms - Cantor Set:")
    cantor = cantor_set_rhythm(iterations=3, length=27)
    print(f"   Cantor set rhythm (first 16): {cantor[:16]}")
    print(f"   Visual: {''.join('█' if x else '░' for x in cantor[:16])}")

    # 18. Geometric Rhythms
    print("\n18. Geometric Rhythms - Golden Ratio:")
    golden = golden_ratio_rhythm(length=21)
    print(f"   Golden ratio rhythm: {golden}")
    print(f"   Visual: {''.join('█' if x else '░' for x in golden)}")

    # 19. Indian Tala System
    print("\n19. Indian Tala System - Teental:")
    teental = Tala("teental")
    teental_pattern = teental.theka_pattern()
    print(f"   Teental (16 matras): {teental_pattern}")
    print(f"   Visual: {''.join('█' if x else '░' for x in teental_pattern)}")

    # 20. West African Timeline
    print("\n20. West African Timeline - Bell Pattern:")
    bell = bell_pattern((12, 8), "standard")
    print(f"   12/8 bell pattern: {bell}")
    print(f"   Visual: {''.join('█' if x else '░' for x in bell)}")

    print("\n=== Demo Complete ===")


# ============================================================================
# Integration with existing system
# ============================================================================

def rhythm_to_composite(pattern: List[int],
                        note_value: int = 60,
                        duration: float = 0.25) -> List[Dict]:
    """
    Convert rhythm pattern to composite structure compatible with your domain.

    Parameters:
    pattern (List[int]): Binary rhythm pattern
    note_value (int): MIDI note number
    duration (float): Duration of each beat in seconds

    Returns:
    List[Dict]: List of note events for composition
    """
    events = []

    for i, value in enumerate(pattern):
        if value > 0:  # Non-zero values create notes
            events.append({
                'type': 'note',
                'time': i * duration,
                'duration': duration,
                'pitch': note_value,
                'velocity': 64 + (value * 20)
                # Scale velocity with value
            })

    return events


def create_rhythmic_composition(algorithm: str = "euclidean",
                                params: Dict = None) -> List[Dict]:
    """
    High-level function to create rhythmic compositions using various algorithms.
    Integrates with your existing music composition system.

    Parameters:
    algorithm (str): Name of rhythm algorithm to use
    params (Dict): Parameters for the algorithm

    Returns:
    List[Dict]: Composition events ready for rendering
    """
    if params is None:
        params = {}

        # Map algorithm names to functions
    algorithm_map = {
        # Euclidean (from existing rhythm.py)
        "euclidean": lambda: euclidean_rhythm(params.get('k', 5), params.get('n', 8)),

        # New algorithms from advanced_rhythm.py
        "clapping": lambda: clapping_music_phases([1, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0], 1)[0],
        "sieve": lambda: xenakis_sieve([3, 4], [[0, 1], [2]], params.get('length', 12)),
        "polyrhythm": lambda: polyrhythm([(3, 8), (2, 8)], 24)[0],
        "genetic": lambda: genetic_rhythm(20, 16, 50,
                                          lambda p: sum(p) / len(p) if len(p) > 0 else 0),
        "fractal": lambda: cantor_set_rhythm(3, 27),
        "tala": lambda: Tala("teental").theka_pattern(),
        "african": lambda: bell_pattern((12, 8), "standard"),
    }

    if algorithm in algorithm_map:
        pattern = algorithm_map[algorithm]()
    else:
        # Default to Euclidean
        pattern = euclidean_rhythm(5, 8)

        # Convert to composition events
    return rhythm_to_composite(pattern)

#============================================================================
# Demo Function
# ============================================================================

def demo_advanced_rhythms():
    """Demonstrate advanced rhythm generation algorithms."""

    print("=== Advanced Rhythmic Pattern Generation Demo ===\n")

    # 1. Clapping Music
    print("1. Clapping Music (Steve Reich):")
    base_pattern = [1, 1, 1, 0, 1, 1, 0, 1, 0, 1, 1, 0]
    phases = clapping_music_phases(base_pattern, 4)
    visualize_patterns(phases[:4], [f"Phase {i}" for i in range(4)])

    # 2. Xenakis Sieve
    print("\n2. Xenakis Sieve Theory:")
    sieve_pattern = xenakis_sieve([3, 4], [[0, 1], [2]], 12)
    print(f"   Sieve(3:{[0, 1]}, 4:{[2]}): {sieve_pattern}")
    print(f"   Visual: {''.join('█' if x else '░' for x in sieve_pattern)}")

    # 3. Polyrhythm
    print("\n3. Polyrhythm (3 against 2):")
    poly_patterns = polyrhythm([(3, 8), (2, 8)], 24)
    visualize_patterns(poly_patterns, ["3/8 layer", "2/8 layer"])

    # 4. Metric Modulation
    print("\n4. Metric Modulation (3:2 ratio):")
    pattern = [1, 0, 1, 0]
    timings = metric_modulation(120, Fraction(3, 2), pattern)
    print(f"   Pattern: {pattern}")
    print(f"   Timings at 120BPM 3:2: {[f'{t:.2f}s' for t in timings]}")

    # 5. Rhythm Necklace
    print("\n5. Rhythm Necklace (all rotations):")
    test_pattern = [1, 0, 1, 0, 0]
    necklaces = rhythm_necklace(test_pattern)
    print(f"   Base: {test_pattern}")
    print(f"   Unique rotations: {len(necklaces)}")

    # 6. Tiling Rhythm
    print("\n6. Rhythmic Tiling (Vuza Canon):")
    pattern_a = [1, 0, 0]
    pattern_b = [0, 1, 0]
    combined, is_tiling = rhythmic_tiling(pattern_a, pattern_b, 9)
    print(f"   Pattern A: {pattern_a}")
    print(f"   Pattern B: {pattern_b}")
    print(f"   Combined: {combined}")
    print(f"   Is tiling: {is_tiling}")

    # 7. Stochastic Rhythm
    print("\n7. Stochastic Rhythm (Gaussian):")
    stochastic = stochastic_rhythm('gaussian', {'mean': 8, 'std': 2}, 16, 0.3)
    print(f"   Gaussian(mean=8, std=2): {stochastic}")
    print(f"   Visual: {''.join('█' if x else '░' for x in stochastic)}")

    # 8. Genetic Algorithm Fitness Function Example
    print("\n8. Genetic Algorithm Setup:")

    def example_fitness(pattern):
        """Example fitness: prefer patterns with alternating beats."""
        score = 0
        for i in range(1, len(pattern)):
            if pattern[i] != pattern[i - 1]:
                score += 1
        return score / (len(pattern) - 1)

    print(f"   Fitness of [1,0,1,0]: {example_fitness([1, 0, 1, 0]):.2f}")
    print(f"   Fitness of [1,1,1,1]: {example_fitness([1, 1, 1, 1]):.2f}")

    # 9. Simple RNN Rhythm
    print("\n9. Simple RNN Rhythm:")
    seed = [1, 0, 1]
    rnn_pattern = simple_rnn_rhythm(seed, [], 10)
    print(f"   Seed: {seed}")
    print(f"   Generated: {rnn_pattern}")

    # 10. Physical Modeling (Logistic Map)
    print("\n10. Chaotic Rhythm (Logistic Map):")
    chaotic = logistic_map_rhythm(3.9, 0.5, 20)
    print(f"   Logistic map (r=3.9): {chaotic}")
    print(f"   Visual: {''.join('█' if x else '░' for x in chaotic)}")

    # 11. Spectral Rhythm Decomposition
    print("\n11. Spectral Rhythm Decomposition:")
    test_pattern = [1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0]
    spectrogram = spectral_rhythm_decomposition(test_pattern, window_size=8)
    reconstructed = rhythm_from_spectrum(spectrogram[:2, :], threshold=0.3)
    print(f"   Original: {test_pattern[:12]}")
    print(f"   Reconstructed: {reconstructed[:12]}")
    print(f"   Spectrogram shape: {spectrogram.shape}")

    # 12. Natural Phenomena - Heartbeat
    print("\n12. Natural Phenomena - Heartbeat Rhythm:")
    heartbeat = heartbeat_rhythm(bpm=80, variability=0.15, duration=5.0)
    print(f"   Heartbeat timings (first 5): {[f'{t:.2f}s' for t in heartbeat[:5]]}")

    # 13. Algorithmic Composition - EMI Style
    print("\n13. Algorithmic Composition - EMI Style Variation:")
    original = [1, 0, 1, 0, 1, 0, 1, 0]
    varied = emi_style_variation(original, similarity=0.8)
    print(f"   Original: {original}")
    print(f"   Varied: {varied}")

    # 14. Microtiming - Swing
    print("\n14. Microtiming - Swing Feel:")
    straight_pattern = [1, 0, 1, 0, 1, 0, 1, 0]
    swung_timings = swing_quantization(straight_pattern, swing_ratio=0.67)
    print(f"   Straight: {straight_pattern}")
    print(f"   Swung timings: {[f'{t:.2f}' for t in swung_timings]}")

    # 15. Data Sonification - Text to Rhythm
    print("\n15. Data Sonification - Text to Rhythm:")
    text = "Hello world this is rhythm"
    text_rhythm = text_to_rhythm(text, mode="syllables")
    print(f"   Text: '{text}'")
    print(f"   Rhythm: {text_rhythm}")
    print(f"   Visual: {''.join('█' if x else '░' for x in text_rhythm)}")


if __name__ == "__main__":
    demo_advanced_rhythms()
