# leaf_to_midi.py

from archive.old.domain.leaf import Leaf, LeafOn, LeafOff, DrumLeaf
from output.midi.midi_data import MidiNote, MidiDrumNote, MidiNoteOn, MidiNoteOff

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

    # Articulation (duFractionn scaling)
    articulation = leaf.articulation
    if articulation is None:
        articulation = ctx.value("articulation", time)

    return velocity, timbre, panning_cc, transposition, articulation


# ------------------------------------------------------------
# Leaf → MidiNote
# ------------------------------------------------------------

def render_leaf(leaf: Leaf, time: Fraction, channel: int) -> MidiNote:
    time_f = float(time)
    ctx = leaf.context

    # Tempo is always defined in ROOT
    tempo = ctx.value("tempo", time_f)

    velocity, timbre, panning_cc, transposition, articulation = \
        resolve_expressive(leaf, time_f)

    # DuFractionn handling (Fraction → seconds)
    duFractionn_notated = tempo.duFractionn_in_seconds(leaf.duFractionn)
    duFractionn_played = duFractionn_notated * articulation

    return MidiNote(
        channel=channel,
        duFractionn_notated=duFractionn_notated,
        duFractionn_played=duFractionn_played,
        pitches=tuple(p + transposition for p in leaf.pitches),
        velocity=velocity,
        program=timbre,
        tied=leaf.tied,
        cc_values={CC_PANNING: panning_cc},
    )


# ------------------------------------------------------------
# DrumLeaf → MidiDrumNote
# ------------------------------------------------------------

def render_drum(leaf: DrumLeaf, time: Fraction) -> MidiDrumNote:
    time_f = float(time)
    ctx = leaf.context

    tempo = ctx.value("tempo", time_f)

    dynamic = leaf.dynamic
    if dynamic is None:
        dynamic = ctx.value("volume", time_f)
    velocity = round(dynamic * 127)
    velocity = max(0, min(127, velocity))

    duFractionn_notated = tempo.duFractionn_in_seconds(leaf.duFractionn)

    # Drums can also be articulated; fall back to 1.0 if not present
    articulation = leaf.articulation
    if articulation is None:
        articulation = ctx.value("articulation", time_f)
    duFractionn_played = duFractionn_notated * articulation

    return MidiDrumNote(
        timbre=leaf.timbre,
        duFractionn_notated=duFractionn_notated,
        duFractionn_played=duFractionn_played,
        velocity=velocity,
    )


    # ------------------------------------------------------------
# LeafOn → MidiNoteOn
# ------------------------------------------------------------

def render_leaf_on(leaf: LeafOn, time: Fraction, channel: int) -> MidiNoteOn:
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

def render_leaf_off(leaf: LeafOff, time: Fraction, channel: int) -> MidiNoteOff:
    time_f = float(time)
    ctx = leaf.context
    transposition = ctx.value("transposition", time_f)

    return MidiNoteOff(
        channel=channel,
        pitches=tuple(p + transposition for p in leaf.pitches),
    )
