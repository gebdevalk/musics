import asyncio
import time
from collections import defaultdict

import mido
from dataclasses import dataclass
from typing import List, Optional

from core.domain.composite import Composite, Concurrent
from core.domain.leafs import Leaf, LeafOn, LeafOff, Algorithm
from core.domain.meta import Part, Meta
from core.domain.score import SCORE
from midi.leaf_to_midi import (
    render_leaf,
    render_leaf_on,
    render_leaf_off, MidiNote, MidiNoteOn, MidiNoteOff,
)
from tools.ratio import Ratio


@dataclass
class ScheduledEvent:
    offset: float          # time offset (seconds or beats, depending on your Ratio semantics)
    event: object          # MidiNote | MidiNoteOn | MidiNoteOff
    channel: int


class MidiEngineAsync:
    """
    Fully non-blocking asyncio-based MIDI engine.

    - Leaf / LeafOn / LeafOff: scheduled immediately
    - Composite: sequential container, iterated with `for child in part`
    - Concurrent: parallel container, children started at same offset
    - Algorithm: expanded via _generate()
    - No global timeline; events are scheduled as they are rendered.
    """
    def __init__(self):
        """
        midi_out: synchronous function that sends a MIDI event
                  (e.g. to a MIDI port or file writer).
        """
        self.midi_out = None
        self.tasks: List[asyncio.Task] = []
        self.channel_numbers = [i for i in range(15, -1, -1) if i != 9]
        self.sounding_note_count: dict[int, dict[int, int]] = (
            defaultdict(lambda: defaultdict(int)))

    # --------------------------------------------------------
    # MIDI port management
    # --------------------------------------------------------

    def open_port(self):
        self.midi_out = mido.open_output()
        return None


    def close_port(self):
        if self.midi_out is not None:
            self.midi_out.close()

    # --------------------------------------------------------
    # Public API
    # --------------------------------------------------------


    async def play(self, part: Part):
        """
        Walks the Part tree and schedules events immediately.
        Returns when all scheduled events have been played.
        """
        start_time = time.time()
        await self.perform(part, SCORE, Ratio(0), 0, start_time)

        if self.tasks:
            await asyncio.gather(*self.tasks)

    # --------------------------------------------------------
    # Tree walk
    # --------------------------------------------------------

    async def perform(self, part: Part, meta: Meta, offset: Ratio, channel: int, start_time: float):
        """
        Dispatch based on Part type:
        - Leaf / LeafOn / LeafOff: render + schedule
        - Composite- > sequential (iteration)
        - Concurrent ->  parallel (iteration)
        - Algorithm -> expand via _generate()
        """

        # -----------------------------
        # Leaf
        # -----------------------------
        if isinstance(part, Leaf):
            midi: MidiNote = render_leaf(part, meta, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        # -----------------------------
        # Meta events
        # -----------------------------
        if isinstance(part, LeafOn):
            midi: MidiNoteOn = render_leaf_on(part, meta, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        if isinstance(part, LeafOff):
            midi: MidiNoteOff = render_leaf_off(part, meta, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        # -----------------------------
        # Composite (sequential)
        # -----------------------------
        if isinstance(part, Composite):
            t = offset
            channel = self.channel_numbers.pop()
            for child in part:  # iteration over children
                await self.perform(child, part, t, channel, start_time)
                t += child.duration
            self.channel_numbers.append(channel)
            return

        # -----------------------------
        # Concurrent (parallel)
        # -----------------------------
        if isinstance(part, Concurrent):
            subtasks: List[asyncio.Task] = []
            for child in part:  # iteration over children
                # all children start at the same offset
                subtasks.append(
                    asyncio.create_task(self.perform(child, SCORE, offset, channel, start_time))
                )
            if subtasks:
                await asyncio.gather(*subtasks)
            return

        # -----------------------------
        # Algorithm
        # -----------------------------
        if isinstance(part, Algorithm):
            generated = part.generate()
            t = offset
            for child in generated:
                await self.perform(child, meta, t, channel, start_time)
                t += child.duration
            return

        raise TypeError(f"Unknown Part type: {type(part)}")

    # --------------------------------------------------------
    # Sounding notes registration
    # --------------------------------------------------------

    def register(self, channel, pitch):

        if pitch > 0:
            self.sounding_note_count[channel][pitch] += 1

    def unregister(self, channel, pitch):
        count = self.sounding_note_count[channel].get(pitch, 0)
        if count > 1:
            self.sounding_note_count[channel][pitch] -= 1
        elif count == 1:
            del self.sounding_note_count[channel][pitch]
        # count == 0: already unregistered, do nothing (or raise)

    def sounding_count(self, channel, pitch):
        return self.sounding_note_count.get(channel, {}).get(pitch, 0)


    # --------------------------------------------------------
    # Async scheduling
    # --------------------------------------------------------

    # def _schedule_event(self, ev: ScheduledEvent, start_time: float):
    #     """
    #     Create an asyncio task that waits until event time and then fires it.
    #     """
    #     task = asyncio.create_task(self._run_event(ev, start_time))
    #     self.tasks.append(task)
    #
    # async def _run_event(self, ev: ScheduledEvent, start_time: float):
    #     """
    #     Async worker: wait until event time, then send the MIDI event.
    #     """
    #     now = time.time()
    #     delay = start_time + ev.offset - now
    #     if delay > 0:
    #         await asyncio.sleep(delay)
    #     self.midi_out(ev.event)

    async def play_note(self, channel: int, note: MidiNote):
         # start note_off task
         asyncio.create_task(
             self.note_off_after_delay(channel, note)
         )
         for pitch in note.pitches:
             self.note_on(channel, pitch, note.velocity)
         await asyncio.sleep(note.duration / 1000)

    async def note_off_after_delay(self, channel: int, note: MidiNote):
        await asyncio.sleep(note.delay / 1000)
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def note_on(self, channel, pitch, velocity):
        self.register(channel, pitch)
        msg = mido.Message(
            'note_on',
            note=pitch,
            velocity=velocity,
            channel=channel
        )
        self.midi_out.send(msg)

    def note_off(self, channel, pitch):
        if self.sounding_count(channel, pitch) == 1:
            msg = mido.Message(
                'note_off',
                note=pitch,
                velocity=0,
                channel=channel
            )
            self.midi_out.send(msg)
        self.unregister(channel, pitch)