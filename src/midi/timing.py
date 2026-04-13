# timing.py

import random
from tools.ratio import Ratio


def musical_to_seconds(ctx, offset: Ratio, step: Ratio = Ratio(1, 64)) -> float:
    """
    Integrate tempo envelope over musical time [0, offset].
    ctx.value("tempo", t_f) must return a Tempo object with duration_in_seconds(duration: Ratio).
    """
    t = Ratio(0)
    seconds = 0.0
    while t < offset:
        remaining = offset - t
        d = step if remaining > step else remaining
        t_f = float(t)
        tempo = ctx.value("tempo", t_f)
        seconds += tempo.duration_in_seconds(d)
        t += d
    return seconds


def compute_onset(start_time: float, ctx, offset: Ratio, micro_on: float) -> float:
    """
    Real-time onset = engine start + integrated tempo + microtiming.
    micro_on is in seconds (can be negative).
    """
    musical_sec = musical_to_seconds(ctx, offset)
    return start_time + musical_sec + micro_on


def compute_noteoff_time(onset_time: float, duration_played: float, micro_off: float) -> float:
    """
    Real-time note-off = onset + played duration + microtiming for release.
    """
    return onset_time + duration_played + micro_off


def compute_drum_noteoff_time(onset_time: float, duration_played: float, micro_off: float) -> float:
    return onset_time + duration_played + micro_off


def compute_micro_on(ctx, time_f: float, offset: Ratio) -> float:
    """
    Per-voice microtiming for onset, in seconds.
    Combines:
    - micro_on envelope (seconds)
    - humanize (ms, random jitter)
    """
    micro = ctx.value("micro_on", time_f)
    if micro is None:
        micro = ctx.value("micro", time_f) or 0.0

    human = ctx.value("humanize", time_f)
    if human:
        micro += random.uniform(-human, human) / 1000.0

    return micro


def compute_micro_off(ctx, time_f: float, offset: Ratio) -> float:
    """
    Per-voice microtiming for note-off, in seconds.
    """
    micro_off = ctx.value("micro_off", time_f)
    return micro_off or 0.0
