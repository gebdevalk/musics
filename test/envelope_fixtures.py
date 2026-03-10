# envelope_fixtures.py
import pytest
from core.domain import Envelope

@pytest.fixture
def basic_envelope():
    """Fixture providing a basic envelope with linear points."""
    env = Envelope(10.0)
    env.add(0.0, 0.0, "linear")
    env.add(5.0, 50.0, "linear")
    env.add(10.0, 100.0, "linear")
    return env

@pytest.fixture
def step_envelope():
    """Fixture providing an envelope with step interpolation."""
    env = Envelope(10.0)
    env.add(0.0, 0.0, "step")
    env.add(5.0, 50.0, "step")
    env.add(10.0, 100.0, "step")
    return env

@pytest.fixture
def mixed_envelope():
    """Fixture providing an envelope with mixed interpolation."""
    env = Envelope(10.0)
    env.add(0.0, 0.0, "step")
    env.add(5.0, 50.0, "linear")
    env.add(10.0, 100.0, "linear")
    return env