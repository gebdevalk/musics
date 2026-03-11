"""
Rule-Based / Formal Systems for Melodic Generation
===================================================
1. Markov Chain
2. L-System
3. Generative Grammar
4. Constraint Satisfaction
"""

import random
from collections import defaultdict


# ─── Note Utilities ────────────────────────────────────────────────────────────

CHROMATIC = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']

def build_scale(root: str, intervals: list) -> list:
    root_idx = CHROMATIC.index(root)
    return [CHROMATIC[(root_idx + i) % 12] for i in intervals]

C_MAJOR      = build_scale('C', [0, 2, 4, 5, 7, 9, 11])
A_MINOR      = build_scale('A', [0, 2, 3, 5, 7, 8, 10])
C_PENTATONIC = build_scale('C', [0, 2, 4, 7, 9])


# ══════════════════════════════════════════════════════════════════════════════
# 1. MARKOV CHAIN
# ══════════════════════════════════════════════════════════════════════════════

class MarkovMelody:
    """
    Builds a transition table from a training melody, then generates
    new melodies by following learned probabilities.

    order=1 → next note depends on current note only
    order=2 → next note depends on the previous two notes, etc.
    """

    def __init__(self, order: int = 1):
        self.order = order
        self.transitions: dict = defaultdict(list)

    def train(self, melody: list):
        """Learn note transitions from a melody (list of note strings)."""
        for i in range(len(melody) - self.order):
            state = tuple(melody[i : i + self.order])
            self.transitions[state].append(melody[i + self.order])

    def generate(self, length: int = 16, seed: tuple = None) -> list:
        """Generate a melody of `length` notes."""
        if not self.transitions:
            raise RuntimeError("Train the model first.")
        if seed is None:
            seed = random.choice(list(self.transitions.keys()))
        melody = list(seed)
        state  = seed
        for _ in range(length - self.order):
            choices = self.transitions.get(state)
            if not choices:
                # dead-end: jump to a random known state
                state = random.choice(list(self.transitions.keys()))
                continue
            nxt   = random.choice(choices)
            melody.append(nxt)
            state = tuple(melody[-self.order :])
        return melody[:length]


# ══════════════════════════════════════════════════════════════════════════════
# 2. L-SYSTEM
# ══════════════════════════════════════════════════════════════════════════════

class LSystemMelody:
    """
    Lindenmayer system applied to melody.

    Each symbol in the string is rewritten simultaneously each iteration.
    A note_map then translates final symbols into musical pitches.

    Example — Fibonacci-like rules:
        A → AB
        B → AC
        C → A
    """

    def __init__(self, axiom: str, rules: dict, note_map: dict):
        """
        axiom    : starting string, e.g. 'A'
        rules    : rewrite rules, e.g. {'A': 'AB', 'B': 'A'}
        note_map : symbol → note name, e.g. {'A': 'C', 'B': 'E', 'C': 'G'}
        """
        self.axiom    = axiom
        self.rules    = rules
        self.note_map = note_map

    def expand(self, iterations: int = 4) -> str:
        """Expand the axiom for `iterations` rewriting steps."""
        result = self.axiom
        for _ in range(iterations):
            result = ''.join(self.rules.get(ch, ch) for ch in result)
        return result

    def to_melody(self, iterations: int = 4, max_notes: int = 32) -> list:
        """Expand and return as a list of notes (capped at max_notes)."""
        sequence = self.expand(iterations)
        notes = [self.note_map[ch] for ch in sequence if ch in self.note_map]
        return notes[:max_notes]


# ══════════════════════════════════════════════════════════════════════════════
# 3. GENERATIVE GRAMMAR
# ══════════════════════════════════════════════════════════════════════════════

class MelodicGrammar:
    """
    Context-free grammar that produces melodies.

    Non-terminals expand randomly into sequences of other symbols.
    Terminals are concrete notes (or 'REST').

    Example structure:
        S      → PHRASE PHRASE
        PHRASE → RISE FALL | ARCH
        RISE   → C E G | C D E G
        FALL   → G E C | G F E D C
        ARCH   → C E G E C
    """

    def __init__(self, rules: dict, terminals: set):
        """
        rules     : {non_terminal: [[production_1], [production_2], ...]}
        terminals : set of terminal symbols (note names + 'REST')
        """
        self.rules     = rules
        self.terminals = terminals

    def expand(self, symbol: str, depth: int = 0, max_depth: int = 10) -> list:
        """Recursively expand `symbol` into a list of terminals."""
        if symbol in self.terminals or depth >= max_depth:
            return [symbol]
        productions = self.rules.get(symbol)
        if not productions:
            return [symbol]
        chosen = random.choice(productions)
        result = []
        for s in chosen:
            result.extend(self.expand(s, depth + 1, max_depth))
        return result

    def generate(self, start: str = 'S') -> list:
        """Generate a melody from the start symbol, stripping rests."""
        return [n for n in self.expand(start) if n != 'REST']


# ══════════════════════════════════════════════════════════════════════════════
# 4. CONSTRAINT SATISFACTION
# ══════════════════════════════════════════════════════════════════════════════

class ConstraintMelody:
    """
    Builds a melody note-by-note. Each candidate note is only accepted
    when all registered constraints pass.

    Chainable constraints:
        .add_max_leap(n)             — max n scale-degrees between steps
        .add_no_repeat()             — no immediate pitch repetition
        .add_direction_limit(n)      — no more than n steps in same direction
        .add_cadence(note)           — final note must be `note`
    """

    def __init__(self, scale: list, length: int = 16):
        self.scale       = scale
        self.length      = length
        self.constraints = []   # list[callable(melody, candidate) -> bool]

    # ── constraint builders ─────────────────────────────────────────────────

    def add_max_leap(self, max_degrees: int = 3):
        scale = self.scale
        def check(melody, note):
            if not melody or note not in scale or melody[-1] not in scale:
                return True
            return abs(scale.index(note) - scale.index(melody[-1])) <= max_degrees
        self.constraints.append(check)
        return self     # chainable

    def add_no_repeat(self):
        def check(melody, note):
            return not melody or melody[-1] != note
        self.constraints.append(check)
        return self

    def add_direction_limit(self, max_consecutive: int = 3):
        scale = self.scale
        def check(melody, note):
            if len(melody) < 2 or note not in scale or melody[-1] not in scale:
                return True
            new_diff = scale.index(note) - scale.index(melody[-1])
            if new_diff == 0:
                return True
            new_dir = 1 if new_diff > 0 else -1
            # count consecutive same-direction steps at the tail of melody
            count = 0
            for i in range(len(melody) - 1, 0, -1):
                if melody[i] not in scale or melody[i - 1] not in scale:
                    break
                d = scale.index(melody[i]) - scale.index(melody[i - 1])
                if d == 0:
                    break
                if (1 if d > 0 else -1) == new_dir:
                    count += 1
                else:
                    break
            return count < max_consecutive
        self.constraints.append(check)
        return self

    def add_cadence(self, cadence_note: str):
        def check(melody, note):
            if len(melody) == self.length - 1:
                return note == cadence_note
            return True
        self.constraints.append(check)
        return self

    # ── generation ──────────────────────────────────────────────────────────

    def _candidates(self, melody: list) -> list:
        return [n for n in self.scale
                if all(c(melody, n) for c in self.constraints)]

    def generate(self, start: str = None) -> list:
        if start is None:
            start = random.choice(self.scale)
        melody = [start]
        for _ in range(self.length - 1):
            valid = self._candidates(melody)
            if not valid:
                valid = self.scale   # fallback: relax all constraints
            melody.append(random.choice(valid))
        return melody


# ══════════════════════════════════════════════════════════════════════════════
# DEMO
# ══════════════════════════════════════════════════════════════════════════════

def print_melody(label: str, melody: list):
    bar = "─" * 54
    print(f"\n{bar}")
    print(f"  {label}")
    print(f"{bar}")
    print("  " + "  ".join(f"{n:<3}" for n in melody))
    print(f"  ({len(melody)} notes)")


if __name__ == "__main__":

    # 1. Markov Chain ──────────────────────────────────────────────────────────
    training = [
        'C','E','G','E','C','D','F','A','F','D',
        'E','G','B','G','E','C','C','G','E','C',
        'G','A','G','F','E','D','C','E','G','C'
    ]
    mc = MarkovMelody(order=2)
    mc.train(training)
    print_melody("1. Markov Chain  (order=2, trained on C-major phrases)",
                 mc.generate(length=20))

    # 2. L-System ──────────────────────────────────────────────────────────────
    ls = LSystemMelody(
        axiom    = 'A',
        rules    = {'A': 'AB', 'B': 'AC', 'C': 'A'},
        note_map = {'A': 'C', 'B': 'E', 'C': 'G'}
    )
    print_melody("2. L-System  (Fibonacci-like rules → C / E / G)",
                 ls.to_melody(iterations=5, max_notes=24))

    # 3. Generative Grammar ────────────────────────────────────────────────────
    grammar = MelodicGrammar(
        rules = {
            'S':      [['PHRASE', 'PHRASE'],
                       ['PHRASE', 'PHRASE', 'PHRASE']],
            'PHRASE': [['RISE', 'FALL'],
                       ['ARCH'],
                       ['PEDAL']],
            'RISE':   [['C', 'E', 'G'],
                       ['C', 'D', 'E', 'G']],
            'FALL':   [['G', 'E', 'C'],
                       ['G', 'F', 'E', 'D', 'C']],
            'ARCH':   [['C', 'E', 'G', 'E', 'C']],
            'PEDAL':  [['C', 'REST', 'C'],
                       ['G', 'REST', 'G']],
        },
        terminals = {'C', 'D', 'E', 'F', 'G', 'A', 'B', 'REST'}
    )
    print_melody("3. Generative Grammar  (rise / fall / arch / pedal phrases)",
                 grammar.generate())

    # 4. Constraint Satisfaction ───────────────────────────────────────────────
    cs = (
        ConstraintMelody(scale=C_MAJOR, length=16)
        .add_max_leap(max_degrees=2)
        .add_no_repeat()
        .add_direction_limit(max_consecutive=3)
        .add_cadence('C')
    )
    print_melody("4. Constraint Satisfaction  (C major, step-wise, cadence → C)",
                 cs.generate(start='C'))
