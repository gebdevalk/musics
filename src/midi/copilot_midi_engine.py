import asyncio
import time
import mido
from dataclasses import dataclass
from typing import List

from core.domain.composite import Composite, Concurrent
from core.domain.leafs import Leaf, LeafOn, LeafOff
from core.domain.part_meta_score import Part
from midi.leaf_to_midi import (
    render_leaf,
    render_leaf_on,
    render_leaf_off,
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
        self.midi_out = mido.open_output()
        self.tasks: List[asyncio.Task] = []
        # dict[channel: int, dict[pitch: int, count: int]]
        self.sounding_note_count: dict[int, dict[int, int]] = {}

    # --------------------------------------------------------
    # MIDI port management
    # --------------------------------------------------------

    # def open_port():
    #     return


    def close_port(self):
        if self.outport is not None:
            self.outport.close()

    # --------------------------------------------------------
    # Public API
    # --------------------------------------------------------

    async def play(self, part: Part, channel: int = 0):
        """
        Walks the Part tree and schedules events immediately.
        Returns when all scheduled events have been played.
        """
        start_time = time.time()
        await self._walk(part, Ratio(0), channel, start_time)

        if self.tasks:
            await asyncio.gather(*self.tasks)

    # --------------------------------------------------------
    # Tree walk
    # --------------------------------------------------------

    async def _walk(self, part: Part, offset: Ratio, channel: int, start_time: float):
        """
        Dispatch based on Part type:

        - Leaf / LeafOn / LeafOff: render + schedule
        - Composite: sequential (iteration)
        - Concurrent: parallel (iteration)
        - Algorithm: expand via _generate()
        """


        # -----------------------------
        # Leaf
        # -----------------------------
        if isinstance(part, Leaf):
            midi = render_leaf(part, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        # -----------------------------
        # Meta events
        # -----------------------------
        if isinstance(part, LeafOn):
            midi = render_leaf_on(part, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        if isinstance(part, LeafOff):
            midi = render_leaf_off(part, channel, offset)
            self._schedule_event(ScheduledEvent(float(offset), midi, channel), start_time)
            return

        # -----------------------------
        # Composite (sequential)
        # -----------------------------
        if isinstance(part, Composite):
            t = offset
            for child in part:  # iteration over children
                await self._walk(child, t, channel, start_time)
                t += child.duration
            return

        # -----------------------------
        # Concurrent (parallel)
        # -----------------------------
        if isinstance(part, Concurrent):
            subtasks: List[asyncio.Task] = []
            for child in part:  # iteration over children
                # all children start at the same offset
                subtasks.append(
                    asyncio.create_task(self._walk(child, offset, channel, start_time))
                )
            if subtasks:
                await asyncio.gather(*subtasks)
            return

        # -----------------------------
        # Algorithm
        # -----------------------------
        if hasattr(part, "_generate"):
            generated = part._generate()
            t = offset
            for child in generated:
                await self._walk(child, t, channel, start_time)
                t += child.duration
            return

        raise TypeError(f"Unknown Part type: {type(part)}")

    # --------------------------------------------------------
    # Sounding notes registration
    # --------------------------------------------------------

    def register(self, channel_number, pitch):
        if pitch > 0:
            self.sounding_note_count[channel_number][pitch] += 1

    def unregister(self, channel_number, pitch):
        count = self.sounding_note_count[channel_number][pitch]
        if count > 1:
            self.sounding_note_count[channel_number][pitch] -= 1
        else:
            del self.sounding_note_count[channel_number][pitch]

    def sounding_count(self, channel_number, pitch):
        return self.sounding_note_count[channel_number].get(pitch, 0)


    # --------------------------------------------------------
    # Async scheduling
    # --------------------------------------------------------

    def _schedule_event(self, ev: ScheduledEvent, start_time: float):
        """
        Create an asyncio task that waits until event time and then fires it.
        """
        task = asyncio.create_task(self._run_event(ev, start_time))
        self.tasks.append(task)

    async def _run_event(self, ev: ScheduledEvent, start_time: float):
        """
        Async worker: wait until event time, then send the MIDI event.
        """
        now = time.time()
        delay = start_time + ev.offset - now
        if delay > 0:
            await asyncio.sleep(delay)
        self.midi_out(ev.event)

