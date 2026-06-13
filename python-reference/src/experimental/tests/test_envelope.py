
import pytest
from experimental.core.envelope import Envelope, Point


def segment_types(points):
    """Helper to extract segment types from a list of points."""
    return [p.type for p in points]


def test_reverse_mirrored_shape():
    """
    Original shape:
        step → up → up → down → step

    Expected mirrored shape:
        step → up → down → down → step
    """

    # Construct envelope with values that produce the desired shape
    # Values: flat → up → sharper up → down → flat
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0, "step"), # flat
            Point(1, 2, "up"), # up
            Point(2, 5, "up"), # sharper up
            Point(3, 3, "down"), # down
            Point(4, 3, "step"), # flat
        ]
    )

    rev = env.reverse()

    # Extract types after reverse
    types = segment_types(rev.points)

    # Expected mirrored sequence
    expected = ["step", "up", "down", "down", "step"]

    assert types == expected


def test_reverse_preserves_values():
    """Reversing must keep the same values, only reorder them."""
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0),
            Point(1, 2),
            Point(2, 5),
            Point(3, 3),
            Point(4, 3),
        ]
    )

    rev = env.reverse()

    # Values should be reversed in order
    assert [p.value for p in rev.points] == [3, 3, 5, 2, 0]


def test_reverse_time_positions():
    """Reversed times must be duration - original_time."""
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0),
            Point(1, 2),
            Point(2, 5),
        ]
    )

    rev = env.reverse()

    assert [p.time for p in rev.points] == [2, 3, 4] # sorted after reversal
