import pytest
from core.domain.point_envelope import Envelope, Point, IP


# ------------------------------------------------------------
# Basic behavior
# ------------------------------------------------------------

def test_empty_envelope_returns_none():
    env = Envelope()
    assert env.get(0.0) is None
    assert env.get(10.0) is None
    assert env.is_empty


def test_constant_value_envelope():
    env = Envelope()
    env.add(0.0, 0.5)

    assert env.get(0.0) == 0.5
    assert env.get(1.0) == 0.5
    assert env.get(100.0) == 0.5


def test_single_point_behaves_as_constant():
    env = Envelope()
    env.add(0.0, 10.0)

    assert env.get(-1.0) == 10.0
    assert env.get(0.0) == 10.0
    assert env.get(1.0) == 10.0


# ------------------------------------------------------------
# Interpolation
# ------------------------------------------------------------

def test_linear_interpolation():
    env = Envelope()
    env.add(0.0, 0.0, IP.LINEAR_UP)
    env.add(1.0, 1.0, IP.LINEAR_UP)

    assert env.get(0.0) == 0.0
    assert pytest.approx(env.get(0.5)) == 0.5
    assert env.get(1.0) == 1.0


def test_step_interpolation():
    env = Envelope()
    env.add(0.0, 10.0, IP.FIXED)
    env.add(1.0, 20.0, IP.STEP)

    # STEP means: hold previous value until next point
    assert env.get(0.0) == 10.0
    assert env.get(0.5) == 10.0
    assert env.get(1.0) == 20.0


def test_clamping_before_first_point():
    env = Envelope()
    env.add(1.0, 10.0)
    env.add(2.0, 20.0)

    assert env.get(0.0) == 10.0
    assert env.get(-10.0) == 10.0


def test_clamping_after_last_point():
    env = Envelope()
    env.add(1.0, 10.0)
    env.add(2.0, 20.0)

    assert env.get(3.0) == 20.0
    assert env.get(100.0) == 20.0


# ------------------------------------------------------------
# Type locking
# ------------------------------------------------------------

def test_type_locking():
    env = Envelope()
    env.add(0.0, 10)

    with pytest.raises(TypeError):
        env.add(1.0, "not a number")


# ------------------------------------------------------------
# Time ordering
# ------------------------------------------------------------

def test_strict_monotonic_time():
    env = Envelope()
    env.add(0.0, 1.0)

    # Negative time is invalid
    with pytest.raises(ValueError):
        env.add(-1.0, 2.0)

    # Duplicate time replaces the point
    env.add(0.0, 3.0)
    assert env.get(0.0) == 3.0
    assert len(env.points) == 1

    # Backwards time is invalid
    with pytest.raises(ValueError):
        env.add(-0.1, 4.0)



def test_replacing_point_at_same_time():
    env = Envelope()
    env.add(0.0, 1.0)
    env.add(1.0, 2.0)
    env.add(1.0, 3.0)  # replaces previous

    assert env.get(1.0) == 3.0
    assert len(env.points) == 2


# ------------------------------------------------------------
# Reverse behavior
# ------------------------------------------------------------

def test_reverse_empty():
    env = Envelope()
    rev = env.reverse()
    assert rev.is_empty


def test_reverse_single_point():
    env = Envelope()
    env.add(0.0, 42.0)
    rev = env.reverse()

    assert len(rev.points) == 1
    assert rev.points[0].time == 0.0
    assert rev.points[0].value == 42.0


def test_reverse_double_reversal_restores_original():
    env = Envelope()
    env.add(0.0, 0.0, IP.FIXED)
    env.add(1.0, 10.0, IP.LINEAR_UP)
    env.add(2.0, 20.0, IP.EASE_IN)
    env.add(3.0, 30.0, IP.FIXED)

    assert env.to_dict() == env.reverse().reverse().to_dict()


def test_reverse_ip_swapping():
    env = Envelope()
    env.add(0.0, 0.0, IP.FIXED)
    env.add(1.0, 10.0, IP.LINEAR_UP)
    env.add(2.0, 20.0, IP.EASE_IN)
    env.add(3.0, 30.0, IP.FIXED)

    rev = env.reverse()

    # Expected IPs after reversal:
    # original: [FIXED, LINEAR_UP, EASE_IN, FIXED]
    # reversed: [FIXED, EASE_OUT, LINEAR_DOWN, FIXED]
    expected = [IP.FIXED, IP.EASE_OUT, IP.LINEAR_DOWN, IP.FIXED]
    actual = [p.ip for p in rev]

    assert actual == expected
