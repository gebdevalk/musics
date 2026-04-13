# leaf_to_midi.py

from core.domain.leafs import Leaf, LeafOn, LeafOff, DrumLeaf
from midi.midi_data import MidiNote, MidiDrumNote, MidiNoteOn, MidiNoteOff
from tools.ratio import Ratio

CC_PANNING = 10


# ------------------------------------------------------------
# Expressive parameter resolution
# ------------------------------------------------------------

def resolve_expressive(leaf: Leaf | LeafOn, time: float):
    """
    Resolve expressive parameters using:
    1. Leaf overrides
    2. Leaf.context inheritance chain
    3. ROOT defaults (guaranteed by Context)
    """

    ctx = leaf.context

    # Dynamic → velocity scaling
    dynamic = leaf.dynamic
    if dynamic is None:
        dynamic = ctx.value("volume", time)
    velocity = round(dynamic * 127)
    velocity = max(0, min(127, velocity))

    # Timbre (program)
    timbre = leaf.timbre
    if timbre is None:
        timbre = ctx.value("timbre", time)

    # Panning: domain uses [-1,1], MIDI uses [0,127]
    panning = getattr(leaf, "panning", None)
    if panning is None:
        panning = ctx.value("panning", time)
    panning = max(-1.0, min(1.0, panning))
    panning_cc = round((panning + 1.0) * 63.5)

    # Transposition
    transposition = ctx.value("transposition", time)

    # Articulation (duration scaling)
    articulation = leaf.articulation
    if articulation is None:
        articulation = ctx.value("articulation", time)

    return velocity, timbre, panning_cc, transposition, articulation


# ------------------------------------------------------------
# Leaf → MidiNote
# ------------------------------------------------------------

def render_leaf(leaf: Leaf, time: Ratio, channel: int) -> MidiNote:
    time_f = float(time)
    ctx = leaf.context

    # Tempo is always defined in ROOT
    tempo = ctx.value("tempo", time_f)

    velocity, timbre, panning_cc, transposition, articulation = \
        resolve_expressive(leaf, time_f)

    # Duration handling (Ratio → seconds)
    duration_notated = tempo.duration_in_seconds(leaf.duration)
    duration_played = duration_notated * articulation

    return MidiNote(
        channel=channel,
        duration_notated=duration_notated,
        duration_played=duration_played,
        pitches=tuple(p + transposition for p in leaf.pitches),
        velocity=velocity,
        program=timbre,
        tied=leaf.tied,
        cc_values={CC_PANNING: panning_cc},
    )


# ------------------------------------------------------------
# DrumLeaf → MidiDrumNote
# ------------------------------------------------------------

def render_drum(leaf: DrumLeaf, time: Ratio) -> MidiDrumNote:
    time_f = float(time)
    ctx = leaf.context

    tempo = ctx.value("tempo", time_f)

    dynamic = leaf.dynamic
    if dynamic is None:
        dynamic = ctx.value("volume", time_f)
    velocity = round(dynamic * 127)
    velocity = max(0, min(127, velocity))

    duration_notated = tempo.duration_in_seconds(leaf.duration)

    # Drums can also be articulated; fall back to 1.0 if not present
    articulation = leaf.articulation
    if articulation is None:
        articulation = ctx.value("articulation", time_f)
    duration_played = duration_notated * articulation

    return MidiDrumNote(
        timbre=leaf.timbre,
        duration_notated=duration_notated,
        duration_played=duration_played,
        velocity=velocity,
    )


    # ------------------------------------------------------------
# LeafOn → MidiNoteOn
# ------------------------------------------------------------

def render_leaf_on(leaf: LeafOn, time: Ratio, channel: int) -> MidiNoteOn:
    time_f = float(time)
    velocity, timbre, panning_cc, transposition, _ = \
        resolve_expressive(leaf, time_f)

    return MidiNoteOn(
        channel=channel,
        pitches=tuple(p + transposition for p in leaf.pitches),
        velocity=velocity,
        program=timbre,
        cc_values=[(CC_PANNING, panning_cc)],
    )


# ------------------------------------------------------------
# LeafOff → MidiNoteOff
# ------------------------------------------------------------

def render_leaf_off(leaf: LeafOff, time: Ratio, channel: int) -> MidiNoteOff:
    time_f = float(time)
    ctx = leaf.context
    transposition = ctx.value("transposition", time_f)

    return MidiNoteOff(
        channel=channel,
        pitches=tuple(p + transposition for p in leaf.pitches),
    )
