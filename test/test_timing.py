# test_timing.py

from tools.ratio import Ratio
from midi.timing import (
    musical_to_seconds,
    compute_onset,
    compute_noteoff_time,
    compute_drum_noteoff_time,
)


class DummyTempo:
    def __init__(self, spb: float):
        self._spb = spb
    def duration_in_seconds(self, dur: Ratio) -> float:
        return float(dur) * self._spb


class DummyCtx:
    def __init__(self, tempo):
        self._tempo = tempo
    def value(self, key, t_f):
        if key == "tempo":
            return self._tempo
        return None


def approx(a: float, b: float, eps: float = 1e-3) -> bool:
    return abs(a - b) <= eps


def test_musical_to_seconds_constant_tempo():
    ctx = DummyCtx(DummyTempo(0.5))  # 120 bpm
    offset = Ratio(3, 2)  # 1.5 beats
    sec = musical_to_seconds(ctx, offset)
    assert approx(sec, 0.75)


def test_compute_onset_no_micro():
    ctx = DummyCtx(DummyTempo(1.0))  # 60 bpm
    start = 100.0
    offset = Ratio(2, 1)  # 2 beats → 2 seconds
    onset = compute_onset(start, ctx, offset, 0.0)
    assert approx(onset, 102.0)


def test_compute_onset_with_micro():
    ctx = DummyCtx(DummyTempo(1.0))
    start = 100.0
    offset = Ratio(1, 1)
    onset = compute_onset(start, ctx, offset, 0.02)
    assert approx(onset, 101.02)


def test_noteoff_time():
    onset = 50.0
    duration_played = 0.75
    micro_off = 0.01
    off = compute_noteoff_time(onset, duration_played, micro_off)
    assert approx(off, 50.76)


def test_drum_noteoff_time():
    onset = 10.0
    duration_played = 0.4
    micro_off = -0.005
    off = compute_drum_noteoff_time(onset, duration_played, micro_off)
    assert approx(off, 10.395)
