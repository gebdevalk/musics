import pytest
from experimental.core.envelope import Envelope, Point


def segment_types(points):
    return [p.type for p in points]


# -------------------------------------------------
# add_point tests
# -------------------------------------------------

def test_add_point_inserts_and_sorts():
    env = Envelope(duration=5, points=[])

    env.add_point(3, 30)
    env.add_point(1, 10)
    env.add_point(2, 20)

    assert [p.time for p in env.points] == [1, 2, 3]
    assert [p.value for p in env.points] == [10, 20, 30]


def test_add_point_overwrites_same_time():
    env = Envelope(duration=5, points=[Point(1, 10)])

    env.add_point(1, 99, "linear")

    assert len(env.points) == 1
    assert env.points[0].value == 99
    assert env.points[0].type == "linear"


def test_add_point_clamps_negative_time():
    env = Envelope(duration=5, points=[])
    env.add_point(-10, 42)

    assert env.points[0].time == 0


def test_add_point_extends_duration():
    env = Envelope(duration=2, points=[])
    env.add_point(5, 100)

    assert env.duration == 5


# -------------------------------------------------
# value_at tests
# -------------------------------------------------

def test_value_at_empty():
    env = Envelope(duration=1, points=[])
    assert env.value_at(0.5) is None


def test_value_at_before_first_point():
    env = Envelope(duration=5, points=[Point(2, 10)])
    assert env.value_at(0) == None


def test_value_at_after_last_point():
    env = Envelope(duration=5, points=[Point(2, 10)])
    assert env.value_at(10) == None


def test_value_at_flat_segment():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0, "flat"),
            Point(2, 10, "flat"),
        ],
    )

    assert env.value_at(1) == 0


def test_value_at_linear_segment_midpoint():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0, "linear"),
            Point(2, 10, "flat"),
        ],
    )

    assert env.value_at(1) == pytest.approx(5)


def test_value_at_linear_exact_point():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0, "linear"),
            Point(2, 10, "flat"),
        ],
    )

    assert env.value_at(2) == 10


# -------------------------------------------------
# reverse tests
# -------------------------------------------------

def test_reverse_preserves_values():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0),
            Point(1, 2),
            Point(2, 5),
            Point(3, 3),
            Point(4, 3),
        ],
    )

    rev = env.reverse()

    assert [p.value for p in rev.points] == [3, 3, 5, 2, 0]


def test_reverse_time_positions():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0),
            Point(1, 1),
            Point(2, 2),
        ],
    )

    rev = env.reverse()

    assert [p.time for p in rev.points] == [0,2,3]


def test_reverse_single_point():
    env = Envelope(duration=5, points=[Point(2, 10, "flat")])
    rev = env.reverse()

    assert len(rev.points) == 1
    assert rev.points[0].value == 10
    assert rev.points[0].type == "flat"


# -------------------------------------------------
# FULL SHAPE TEST (requested case)
# flat → linear → linear → linear → flat
# -------------------------------------------------

def test_full_sequence_flat_linear_linear_linear_flat():
    env = Envelope(
        duration=4,
        points=[
            Point(0, 0, "flat"),
            Point(1, 2, "linear"),
            Point(2, 5, "linear"),
            Point(3, 3, "linear"),
            Point(4, 3, "flat"),
        ],
    )

    # --- forward checks ---
    assert segment_types(env.points) == [
        "flat",
        "linear",
        "linear",
        "linear",
        "flat",
    ]

    # interpolation sanity checks
    assert env.value_at(0.5) == 0
    assert env.value_at(1.5) == pytest.approx(3.5)
    assert env.value_at(2.5) == pytest.approx(4)
    assert env.value_at(3.5) == 3

    # --- reverse checks ---
    rev = env.reverse()

    # values reversed
    assert [p.value for p in rev.points] == [3, 3, 5, 2, 0]

    # types recomputed correctly
    assert segment_types(rev.points) == [
        "flat",
        "linear",
        "linear",
        "linear",
        "flat",
    ]

    # reversed interpolation sanity
    assert rev.value_at(0.5) == 3
    assert rev.value_at(2) == pytest.approx(3.5)