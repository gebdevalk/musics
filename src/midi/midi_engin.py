# midi_engine.py

from core.domain.leafs import Leaf

CC_PANNING = 10

class MidiNote:
    def __init__(self, channel, pitches, velocity, duration, articulation, program, cc_values=None):
        self.pitches = pitches              # list[int]
        self.velocity = velocity            # int (0–127)
        self.duration = duration            # float or ticks
        self.articulation = articulation    # float
        self.channel = channel              # int (0–15)
        self.program = program              # int (0–127)
        self.cc_values = cc_values or {}    # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(pitches={self.pitches}, velocity={self.velocity}, "
            f"duration={self.duration}, articulation={self.articulation}, channel={self.channel}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )

def render(channel: int, engine_time: float, leaf: Leaf) -> MidiNote:
    # Get context from leaf's parent
    context = leaf.parent
    
    # Resolve values with fallbacks
    if leaf.volume is not None:
        volume = leaf.volume
    elif context is not None and hasattr(context, 'get'):
        volume = context.get("volume", 50)  # Default from PARAM_CONFIG
    else:
        volume = 50
    
    if leaf.dynamic is not None:
        dynamic = leaf.dynamic
    elif context is not None and hasattr(context, 'get'):
        dynamic = context.get("dynamic", 0)  # Default from PARAM_CONFIG
    else:
        dynamic = 0
    
    if leaf.timbre is not None:
        timbre = leaf.timbre
    elif context is not None and hasattr(context, 'get'):
        timbre = context.get("timbre", 0)  # Default from PARAM_CONFIG
    else:
        timbre = 0
    
    # Handle panning
    if leaf.panning is not None:
        panning = leaf.panning
    elif context is not None and hasattr(context, 'get'):
        panning = context.get("panning", 0.0)
    else:
        panning = 0.0
    
    # Calculate velocity
    velocity = round(1.27 * (volume + dynamic))
    # Clamp velocity to MIDI range
    velocity = max(0, min(127, velocity))
    
    # Calculate panning CC value
    panning_cc = round(((panning + 1.0) / 2.0) * 127)
    panning_cc = max(0, min(127, panning_cc))
    
    return MidiNote(channel=channel,
                    pitches=leaf.pitches,
                    velocity=velocity,
                    duration=leaf.duration,
                    articulation=leaf.articulation,
                    program=timbre,
                    cc_values={CC_PANNING: panning_cc})

"""
domain: iterator produces Leaves
engine: render() evaluates Leaves at engine_time

leaf = iterator.next()

render(channel, engine_time, leaf):
    mod = leaf.evaluate(engine_time)

program = resolve_timbre(leaf, context)

if program != channel_state.last_program:
    emit_program_change(program)
    channel_state.last_program = program

"""

def resolve_timbre(leaf, context, global_timbre=0):
    if leaf.timbre is not None:
        return leaf.timbre
    if context.timbre is not None:
        return context.timbre
    return global_timbre


class Player:
    def __init__(self, iterator):
        self.iterator = iterator
        self.current_leaf = None

    def pull(self, engine_time):
        # If we don’t have a leaf yet, get one
        if self.current_leaf is None:
            self.current_leaf = self.iterator.next()

        # Evaluate the leaf at the current engine time
        value = self.current_leaf.evaluate(engine_time)

        # If the leaf has finished, advance the iterator
        if engine_time >= self.current_leaf.end_time:
            self.current_leaf = self.iterator.next()

        return value

