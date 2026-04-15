import pytest
from core.domain.point_envelope import Envelope, Point, IP


# ------------------------------------------------------------
# Basic behavior
# ------------------------------------------------------------

def test_empty_envelope_returns_none():
    """
    An empty envelope has no points.
    get() must return None for any time.
    """
    env = Envelope()
    assert env.get(0.0) is None
    assert env.get(10.0) is None
    assert env.is_empty


def test_constant_value_envelope():
    """
    A single point defines a constant function.
    After the last point, the value is held.
    """
    env = Envelope()
    env.append(0.0, 0.5)

    assert env.get(0.0) == 0.5
    assert env.get(1.0) == 0.5
    assert env.get(100.0) == 0.5


def test_single_point_behaves_as_constant():
    """
    Before the first point, the value is clamped to the first point.
    """
    env = Envelope()
    env.append(0.0, 10.0)

    assert env.get(-1.0) == 10.0
    assert env.get(0.0) == 10.0
    assert env.get(1.0) == 10.0


# ------------------------------------------------------------
# Interpolation
# ------------------------------------------------------------

def test_linear_interpolation():
    """
    LINEAR_UP means linear interpolation between points.
    """
    env = Envelope()
    env.append(0.0, 0.0, IP.LINEAR_UP)
    env.append(1.0, 1.0, IP.LINEAR_UP)

    assert env.get(0.0) == 0.0
    assert pytest.approx(env.get(0.5)) == 0.5
    assert env.get(1.0) == 1.0


def test_step_interpolation():
    """
    STEP interpolation holds the previous value until the next point.
    """
    env = Envelope()
    env.append(0.0, 10.0, IP.FIXED)
    env.append(1.0, 20.0, IP.STEP)

    assert env.get(0.0) == 10.0
    assert env.get(0.5) == 10.0
    assert env.get(1.0) == 20.0


def test_clamping_before_first_point():
    """
    Before the first point, the envelope clamps to the first value.
    """
    env = Envelope()
    env.append(1.0, 10.0)
    env.append(2.0, 20.0)

    assert env.get(0.0) == 10.0
    assert env.get(-10.0) == 10.0


def test_clamping_after_last_point():
    """
    After the last point, the envelope holds the last value.
    """
    env = Envelope()
    env.append(1.0, 10.0)
    env.append(2.0, 20.0)

    assert env.get(3.0) == 20.0
    assert env.get(100.0) == 20.0


# ------------------------------------------------------------
# Type locking
# ------------------------------------------------------------

def test_type_locking():
    """
    The first added value defines the envelope's type.
    Adding a different type must raise TypeError.
    """
    env = Envelope()
    env.append(0.0, 10)

    with pytest.raises(TypeError):
        env.append(1.0, "not a number")


# ------------------------------------------------------------
# Time ordering
# ------------------------------------------------------------

def test_strict_monotonic_time():
    """
    Times must be non-decreasing.
    Duplicate times replace the previous point.
    """
    env = Envelope()
    env.append(0.0, 1.0)

    # Negative time is invalid
    with pytest.raises(ValueError):
        env.append(-1.0, 2.0)

    # Duplicate time replaces the point
    env.append(0.0, 3.0)
    assert env.get(0.0) == 3.0
    assert len(env.points) == 1

    # Backwards time is invalid
    with pytest.raises(ValueError):
        env.append(-0.1, 4.0)


def test_replacing_point_at_same_time():
    """
    Adding a point at the same time replaces the previous point.
    """
    env = Envelope()
    env.append(0.0, 1.0)
    env.append(1.0, 2.0)
    env.append(1.0, 3.0)  # replaces previous

    assert env.get(1.0) == 3.0
    assert len(env.points) == 2


# ------------------------------------------------------------
# Reverse behavior
# ------------------------------------------------------------

def test_reverse_empty():
    """
    Reversing an empty envelope yields an empty envelope.
    """
    env = Envelope()
    rev = env.reverse()
    assert rev.is_empty


def test_reverse_single_point():
    """
    A single point reversed stays the same.
    """
    env = Envelope()
    env.append(0.0, 42.0)
    rev = env.reverse()

    assert len(rev.points) == 1
    assert rev.points[0].time == 0.0
    assert rev.points[0].value == 42.0


def test_reverse_double_reversal_restores_original():
    """
    reverse(reverse(env)) must equal env.
    This is the most important reversal invariant.
    """
    env = Envelope()
    env.append(0.0, 0.0, IP.FIXED)
    env.append(1.0, 10.0, IP.LINEAR_UP)
    env.append(2.0, 20.0, IP.EASE_IN)
    env.append(3.0, 30.0, IP.FIXED)

    assert env.to_dict() == env.reverse().reverse().to_dict()


def test_reverse_ip_swapping():
    """
    IPs must reverse correctly:
    LINEAR_UP <-> LINEAR_DOWN
    EASE_IN <-> EASE_OUT
    FIXED stays FIXED
    """
    env = Envelope()
    env.append(0.0, 0.0, IP.FIXED)
    env.append(1.0, 10.0, IP.LINEAR_UP)
    env.append(2.0, 20.0, IP.EASE_IN)
    env.append(3.0, 30.0, IP.FIXED)

    rev = env.reverse()

    expected = [IP.FIXED, IP.EASE_OUT, IP.LINEAR_DOWN, IP.FIXED]
    actual = [p.ip for p in rev]

    assert actual == expected


def test_reverse_value_mirroring():
    """
    Values must appear in reverse order after reversal.
    """
    env = Envelope()
    env.append(0.0, 1)
    env.append(1.0, 2)
    env.append(2.0, 3)

    rev = env.reverse()

    assert [p.value for p in rev] == [3, 2, 1]


def test_reverse_time_mirroring():
    """
    Times must be mirrored around the envelope duration.
    If original times are [0, 1, 2], duration=2,
    reversed times must be [0, 1, 2].
    """
    env = Envelope()
    env.append(0.0, 1)
    env.append(1.0, 2)
    env.append(2.0, 3)

    rev = env.reverse()

    assert [p.time for p in rev] == [0.0, 1.0, 2.0]


def test_self_reversing_ips():
    """
    IPs like FIXED, SMOOTH, STEP are self-reversing.
    Double reversal must preserve them.
    """
    env = Envelope()
    env.append(0.0, 0.0, IP.FIXED)
    env.append(1.0, 1.0, IP.SMOOTH)
    env.append(2.0, 2.0, IP.STEP)
    env.append(3.0, 3.0, IP.FIXED)

    assert env.to_dict() == env.reverse().reverse().to_dict()
