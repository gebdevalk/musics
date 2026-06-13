# domain.py
from dataclasses import dataclass
from abc import ABC


# ============================================================
# CONTEXT AND COMPOSITE MODEL
# ============================================================

# Context has Context
# Component(Context)
# ├── Composite(Context,Component,List) has *Component
# │ ├── Chain
# │ └── Bag
# │ └── Concurrent(Component) has *Composite
# │
# ├── Leaf(Component)
# ├── ├── Sequence(Leaf)
# │   └── Algorithm(Leaf)
# ├── Note(Leaf)
# ├── Chord(Leaf)
# ├── Rest(Leaf)
# └── Primitives(Leaf)

#  def __init__(self, time: float, value: T, ip: IP = IP.FIXED):

@dataclass(slots=False)
class Context:
    def __init__(self, **props):
        self.props = props # interpretation modifiers


class Component(ABC):
    def __init__(self, context: Context):
        self.parent = context

class Composite(Component):
    def __init__(self, ctx):

        self.context = list(children)

    def __iter__(self):
        return iter(self.children)

    def append(self, child):
        self.children.append(child)


# ============================================================
# ATOMS (REAL LEAFS)
# ============================================================

class Atom:
    """
    Atoms are the only real leafs of the structure.
    They produce MIDI events. They have no children.
    """
    def to_events(self, ctx):
        raise NotImplementedError


class Note(Atom):
    def __init__(self, pitch, velocity=100, duration=120):
        self.pitch = pitch
        self.velocity = velocity
        self.duration = duration

    def to_events(self, ctx):
        # Apply context modifiers (transpose, velocity scale, etc.)
        pitch = self.pitch + ctx.get("transpose", 0)
        vel = int(self.velocity * ctx.get("velocity_scale", 1.0))
        ch = ctx.get("channel", 0)

        return [
            ("note_on", pitch, vel, ch, 0),
            ("note_off", pitch, vel, ch, self.duration)
        ]


class Rest(Atom):
    def __init__(self, duration):
        self.duration = duration

    def to_events(self, ctx):
        return [("rest", None, None, None, self.duration)]


# ============================================================
# COMPOSITE TYPES
# ============================================================

class Chain(Composite):
    """Sequential container."""
    pass


class Concurrent(Composite):
    """Parallel container (children must be composites)."""
    pass


class Chord(Composite):
    """Simultaneous notes (children must be atoms)."""
    pass


class Algorithm(Composite):
    """Opaque generator (no children)."""
    def __init__(self, generator_fn, **props):
        super().__init__(**props)
        self.generator_fn = generator_fn

    def generate(self, ctx):
        # returns a list of Atoms
        return self.generator_fn(ctx)


class Score(Composite):
    """Top-level parallel container."""
    pass


# ============================================================
# STRUCT (COMPACT SHAPE SIGNATURE)
# ============================================================

def struct(node):
    """
    Compact structural fingerprint:
    - Atoms disappear
    - Composite nodes show only their brackets
    - Context properties do not appear
    """
    if isinstance(node, Atom):
        return "" # atoms vanish

    if isinstance(node, Chain):
        return "(" + "".join(struct(c) for c in node) + ")"

    if isinstance(node, Concurrent):
        return '"' + "(" + "".join(struct(c) for c in node) + ")" + '"'

    if isinstance(node, Chord):
        return "<>"

    if isinstance(node, Algorithm):
        return "@()"

    if isinstance(node, Score):
        return "(" + "".join(struct(c) for c in node) + ")"

    if isinstance(node, Context):
        return "^()"

    return ""


# ============================================================
# MIDI STREAM COMPILER
# ============================================================

def compile_stream(node, inherited_ctx=None):
    """
    Converts the composite tree into a flat list of MIDI events.
    """
    if inherited_ctx is None:
        inherited_ctx = {}

    # Merge context properties
    ctx = inherited_ctx.copy()
    if isinstance(node, Context):
        ctx.update(node.props)

    # Atom → direct events
    if isinstance(node, Atom):
        return node.to_events(ctx)

    # Algorithm → generate atoms
    if isinstance(node, Algorithm):
        atoms = node.generate(ctx)
        events = []
        for a in atoms:
            events.extend(a.to_events(ctx))
        return events

    # Composite containers
    if isinstance(node, Chain):
        events = []
        for child in node:
            events.extend(compile_stream(child, ctx))
        return events

    if isinstance(node, Concurrent):
        # merge streams in parallel
        merged = []
        for child in node:
            merged.extend(compile_stream(child, ctx))
        return merged

    if isinstance(node, Chord):
        # all atoms start at same time
        merged = []
        for atom in node:
            merged.extend(atom.to_events(ctx))
        return merged

    if isinstance(node, Score):
        merged = []
        for part in node:
            merged.extend(compile_stream(part, ctx))
        return merged

    return []


# ============================================================
# SIMPLE MIDI PLAYER (EVENT SCHEDULER)
# ============================================================

import time

def play_midi(events):
    """
    A minimal MIDI scheduler.
    Expects events as tuples:
    (type, pitch, velocity, channel, delta_time)
    """
    current_time = 0

    for ev in events:
        ev_type, pitch, vel, ch, dt = ev
        time.sleep(dt / 1000.0) # convert ms to seconds
        current_time += dt

        if ev_type == "note_on":
            print(f"[{current_time}] NOTE ON ch={ch} pitch={pitch} vel={vel}")
        elif ev_type == "note_off":
            print(f"[{current_time}] NOTE OFF ch={ch} pitch={pitch} vel={vel}")
        elif ev_type == "rest":
            print(f"[{current_time}] REST {dt}ms")


# ============================================================
# EXAMPLE USAGE
# ============================================================

melody = Chain(
    Note(60, 100, 200),
    Note(62, 100, 200),
    Note(64, 100, 200)
)

harmony = Chord(
    Note(48, 80, 600),
    Note(52, 80, 600),
    Note(55, 80, 600)
)

score = Score(
    melody,
    harmony
)

print("STRUCT:", struct(score))
events = compile_stream(score)
play_midi(events)
