# engine.py --- Iterative streaming MIDI engine for the pymusics domain model.
#
# Architecture:
#   Producer: walks the Part tree lazily, resolves leaves against Context,
#             pushes MidiEvent objects with wall-clock onsets to an asyncio.Queue.
#   Consumer: pulls events from the queue, sleeps until onset, sends MIDI via mido.
#   Channel pool: 15 melodic channels + dedicated drum channel 9.
#
# Resolution:
#   velocity = clamp(context.volume + leaf.dynamic, 0, 127)
#   program  = context.timbre
#   pitches  = leaf.pitches + context.transposition
#   duration = musical_duration * context.articulation (or leaf.articulation)
#   panning  = context.panning -> CC10

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from fractions import Fraction
from typing import Optional, TYPE_CHECKING

import mido


from core.domain.parts import Leaf, Rest, Drum, Composite, Part
from core.domain.context import Context

# --- Constants ----------------------------------------------------------------

DRUM_CHANNEL = 9
MELODIC_CHANNELS = [i for i in range(16) if i != DRUM_CHANNEL]  # 0-8, 10-15

CC_PANNING = 10


# --- MidiEvent --- what flows through the queue --------------------------------

@dataclass
class MidiEvent:
    """A fully resolved MIDI event with wall-clock onset (seconds from start)."""
    onset: float
    channel: int
    pitches: tuple[int, ...]
    velocity: int
    duration_played: float          # seconds --- when to send note_off
    program: int
    tied: bool = False
    cc_values: dict[int, int] = field(default_factory=dict)


# --- Channel --- per-channel state tracking (consumer side) --------------------

@dataclass
class Channel:
    number: int
    program: Optional[int] = None
    sounding: set[tuple[int, ...]] = field(default_factory=set)
    cc_cache: dict[int, int] = field(default_factory=dict)

    def reset(self) -> None:
        self.program = None
        self.sounding.clear()
        self.cc_cache.clear()


# --- Channel pool -------------------------------------------------------------

class ChannelPool:
    """Fixed-size pool of 15 melodic MIDI channels, managed as an asyncio.Queue."""

    def __init__(self) -> None:
        self._queue: asyncio.Queue[Channel] = asyncio.Queue()
        for n in MELODIC_CHANNELS:
            self._queue.put_nowait(Channel(number=n))

    async def acquire(self) -> Channel:
        return await self._queue.get()

    def release(self, ch: Channel) -> None:
        ch.reset()
        self._queue.put_nowait(ch)


# --- Resolution helpers -------------------------------------------------------

def _sample(ctx: Context, key: str, time_f: float, default):
    """Sample a context key at a musical time, falling back to *default*."""
    val = ctx.value(key, time_f)
    return val if val is not None else default


def _musical_to_seconds(duration: Fraction, tempo) -> float:
    """Convert a musical duration (Fraction of whole note) to wall-clock seconds."""
    beats = duration / tempo.duration
    return float(beats) * 60.0 / tempo.bpm


def _cumulative_seconds(offset: Fraction, ctx: Context) -> float:
    """Convert cumulative musical offset to wall-clock seconds."""
    tempo = _sample(ctx, "tempo", float(offset), None)
    if tempo is None:
        # Fallback: 120 BPM, quarter-note beat
        return float(offset) * 60.0 / 120.0 * 4.0
    return _musical_to_seconds(offset, tempo)


def _clamp_velocity(value: float) -> int:
    return max(0, min(127, round(value)))


# --- Leaf / Drum -> MidiEvent -------------------------------------------------

def resolve_common(part: Leaf| Drum, offset: Fraction, start_time: float) -> tuple:
    """Resolve a Drum into a wall-clock MidiEvent (always on channel 9)."""
    time_f = float(offset)
    ctx = part.context

    tempo = _sample(ctx, "tempo", time_f, None)
    velocity = _sample(ctx, "volume", time_f, 80)
    articulation = _sample(ctx, "articulation", time_f, 1.0)
    duration_seconds = _musical_to_seconds(part.duration, tempo)
    duration_played = duration_seconds * articulation
    onset = start_time + _cumulative_seconds(offset, ctx)

    return tempo, velocity, articulation, duration_seconds, duration_played, onset


def resolve_leaf(leaf: Leaf, channel_num: int, offset: Fraction,
                 start_time: float) -> MidiEvent:
    """
    Resolve a Leaf into a wall-clock MidiEvent.

    Velocity = context.volume + leaf.dynamic
    """

    tempo, velocity, articulation, duration_seconds, duration_played, onset = (
        resolve_common(leaf, offset, start_time))

    # --- Velocity: base volume + dynamic delta ---
    dynamic_delta = leaf.dynamic if leaf.dynamic is not None else 0
    velocity = _clamp_velocity(velocity + dynamic_delta)

    time_f = float(offset)
    ctx = leaf.context

    # --- Program (timbre) ---
    program = _sample(ctx, "timbre", time_f, 0)

    # --- Transposition ---
    transposition = _sample(ctx, "transposition", time_f, 0)

    # --- Panning -> CC10 (0-127) ---
    panning = _sample(ctx, "panning", time_f, 0.0)
    panning_cc = round((panning + 1.0) * 63.5)
    panning_cc = max(0, min(127, panning_cc))

    return MidiEvent(
        onset=onset,
        channel=channel_num,
        pitches=tuple(p + transposition for p in leaf.pitches),
        velocity=velocity,
        duration_played=duration_played,
        program=program,
        tied=leaf.tied,
        cc_values={CC_PANNING: panning_cc},
    )


def resolve_drum(drum: Drum, offset: Fraction, start_time: float) -> MidiEvent:
    """Resolve a Drum into a wall-clock MidiEvent (always on channel 9)."""

    tempo, velocity, articulation, duration_seconds, duration_played, onset = (
        resolve_common(drum, offset, start_time))

    return MidiEvent(
        onset=onset,
        channel=DRUM_CHANNEL,
        pitches=(drum.program,),
        velocity=velocity,
        duration_played=duration_played,
        program=0,
    )


# --- Iterative tree walker ----------------------------------------------------

async def walk(part: Part, queue: asyncio.Queue, pool: ChannelPool,
               channel: Channel, offset: Fraction,
               start_time: float) -> Fraction:
    """
    Walk a Part tree iteratively. Resolves leaves on-the-fly and pushes
    MidiEvent objects to the queue. Returns the total musical duration walked.

    SEQ: children processed in order, offset accumulated.
    PAR: each child gets its own channel; offset is max child duration.
    """
    if isinstance(part, Leaf):
        event = resolve_leaf(part, channel.number, offset, start_time)
        await queue.put(event)
        return part.duration

    if isinstance(part, Rest):
        return part.duration

    if isinstance(part, Drum):
        event = resolve_drum(part, offset, start_time)
        await queue.put(event)
        return part.duration

    if isinstance(part, Composite):
        if part.type == "SEQ":
            total = Fraction(0)
            for child in part:
                dur = await walk(child, queue, pool, channel, offset, start_time)
                offset += dur
                total += dur
            return total

        if part.type == "PAR":
            async def walk_child(child: Part, off: Fraction) -> Fraction:
                ch = await pool.acquire()
                try:
                    return await walk(child, queue, pool, ch, off, start_time)
                finally:
                    pool.release(ch)

            tasks = [asyncio.create_task(walk_child(c, offset)) for c in part]
            if not tasks:
                return Fraction(0)
            durations = await asyncio.gather(*tasks)
            return max(durations)

        # Unknown composite type -> fall back to sequential
        total = Fraction(0)
        for child in part:
            dur = await walk(child, queue, pool, channel, offset, start_time)
            offset += dur
            total += dur
        return total

    raise TypeError(f"Unknown Part type: {type(part).__name__}")


# --- Engine -------------------------------------------------------------------

class Engine:
    """
    Iterative streaming MIDI engine.

    Usage:
        engine = Engine()
        engine.open_port()
        await engine.play(score)
        engine.close_port()
    """

    def __init__(self, queue_size: int = 256) -> None:
        self.midi_out: Optional[mido.ports.BaseOutput] = None
        self.queue: asyncio.Queue[MidiEvent | None] = asyncio.Queue(
            maxsize=queue_size)
        self.pool = ChannelPool()
        self._note_off_tasks: list[asyncio.Task] = []

    # --- MIDI port ---------------------------------------------------------

    def open_port(self) -> None:
        """Open the first available MIDI output port."""
        self.midi_out = mido.open_output()

    def close_port(self) -> None:
        """Close the MIDI output port."""
        if self.midi_out is not None:
            self.midi_out.close()
            self.midi_out = None

    # --- Public API --------------------------------------------------------

    async def play(self, score: Composite) -> None:
        """
        Play a Composite (the score root): producer walks, consumer sends MIDI.
        Both run concurrently; the queue provides buffering.
        """
        if self.midi_out is None:
            raise RuntimeError("MIDI port not open. Call open_port() first.")

        if not score.children:
            return

        start_time = asyncio.get_running_loop().time()

        # --- Producer ---
        async def produce() -> None:
            root_ch = await self.pool.acquire()
            try:
                await walk(score, self.queue, self.pool, root_ch,
                           Fraction(0), start_time)
            finally:
                self.pool.release(root_ch)
                await self.queue.put(None)   # sentinel

        producer_task = asyncio.create_task(produce())

        # --- Consumer ---
        consumer_task = asyncio.create_task(self._consume(start_time))

        await asyncio.gather(producer_task, consumer_task)

        # Drain remaining note-off tasks
        if self._note_off_tasks:
            await asyncio.gather(*self._note_off_tasks)
            self._note_off_tasks.clear()

    # --- Consumer ----------------------------------------------------------

    async def _consume(self, start_time: float) -> None:
        """Pull events from the queue and send MIDI at the correct wall-clock time."""
        loop = asyncio.get_running_loop()
        ch_state: dict[int, Channel] = {}

        def get_ch(ch_num: int) -> Channel:
            if ch_num not in ch_state:
                ch_state[ch_num] = Channel(number=ch_num)
            return ch_state[ch_num]

        while True:
            event = await self.queue.get()
            if event is None:          # sentinel
                break

            # Sleep until onset
            delay = event.onset - loop.time()
            if delay > 0:
                await asyncio.sleep(delay)

            ch = get_ch(event.channel)

            # Program change
            if event.program != ch.program:
                self._program_change(event.channel, event.program)
                ch.program = event.program

            # Control changes
            for cc, value in event.cc_values.items():
                if ch.cc_cache.get(cc) != value:
                    self._control_change(event.channel, cc, value)
                    ch.cc_cache[cc] = value

            # Note on / tie handling
            if event.channel == DRUM_CHANNEL:
                self._note_on(event.channel, event.pitches[0], event.velocity)
                self._schedule_note_off(
                    event.channel, event.pitches[0],
                    event.onset + event.duration_played,
                )
            else:
                if event.tied:
                    if event.pitches not in ch.sounding:
                        # first of tie chain
                        ch.sounding.add(event.pitches)
                        for p in event.pitches:
                            self._note_on(event.channel, p, event.velocity)
                    # middle of tie chain: silent
                else:
                    if event.pitches in ch.sounding:
                        # end of tie chain
                        ch.sounding.discard(event.pitches)
                        for p in event.pitches:
                            self._note_off(event.channel, p)
                    else:
                        # normal note
                        for p in event.pitches:
                            self._note_on(event.channel, p, event.velocity)
                        self._schedule_note_off(
                            event.channel, event.pitches,
                            event.onset + event.duration_played,
                        )

    # --- MIDI primitives ---------------------------------------------------

    def _note_on(self, channel: int, pitch: int, velocity: int) -> None:
        self.midi_out.send(mido.Message(
            'note_on', channel=channel, note=pitch, velocity=velocity))

    def _note_off(self, channel: int, pitch: int) -> None:
        self.midi_out.send(mido.Message(
            'note_off', channel=channel, note=pitch, velocity=0))

    def _program_change(self, channel: int, program: int) -> None:
        self.midi_out.send(mido.Message(
            'program_change', channel=channel, program=program))

    def _control_change(self, channel: int, control: int, value: int) -> None:
        self.midi_out.send(mido.Message(
            'control_change', channel=channel, control=control, value=value))

    def _schedule_note_off(self, channel: int, pitches: tuple[int, ...],
                           target_time: float) -> None:
        """Background task: send note_off at *target_time*."""

        async def off_task() -> None:
            loop = asyncio.get_running_loop()
            delay = target_time - loop.time()
            if delay > 0:
                await asyncio.sleep(delay)
            for p in pitches:
                self._note_off(channel, p)

        self._note_off_tasks.append(asyncio.create_task(off_task()))
