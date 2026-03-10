# test_envelope.py
import pytest
from core.domain import Envelope, _interpolate_linear


class TestEnvelopeInitialization:
    """Tests for Envelope class initialization"""

    def test_init_with_duration_only(self):
        env = Envelope(duration=5.0)
        assert env.dur == 5.0
        assert len(env.data) == 0

    def test_init_with_duration_and_data(self):
        data = [(0.0, 1.0, "step"), (2.0, 3.0, "linear")]
        env = Envelope(duration=5.0, data=data)
        assert env.dur == 5.0
        assert len(env.data) == 2
        assert env.data == data

    def test_init_negative_duration_raises_error(self):
        with pytest.raises(ValueError, match="Duration cannot be negative"):
            Envelope(duration=-1.0)

    def test_set_negative_duration_raises_error(self):
        env = Envelope(duration=5.0)
        with pytest.raises(ValueError, match="Duration cannot be negative"):
            env.dur = -2.0


class TestEnvelopeAddMethod:
    """Tests for Envelope.add method"""

    def test_add_single_point(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        assert len(env.data) == 1
        assert env.data[0] == (1.0, 10.0, "step")

    def test_add_multiple_points_sorted(self):
        env = Envelope(duration=5.0)
        env.add(time=3.0, value=30.0, type="linear")
        env.add(time=1.0, value=10.0, type="step")
        env.add(time=2.0, value=20.0, type="linear")

        # Verify they're sorted by time
        assert env.data[0] == (1.0, 10.0, "step")
        assert env.data[1] == (2.0, 20.0, "linear")
        assert env.data[2] == (3.0, 30.0, "linear")

    def test_add_with_default_interpolation(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0)  # No type specified
        assert env.data[0][2] == "step"  # Should default to "step"

    def test_add_with_custom_interpolation_type(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="linear")
        assert env.data[0][2] == "linear"


class TestValueAtMethod:
    """Tests for Envelope.value_at method"""

    def test_value_at_empty_envelope(self):
        env = Envelope(duration=5.0)
        assert env.value_at(2.0) is None

    def test_value_at_before_first_point(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        assert env.value_at(0.5) is None

    def test_value_at_exactly_first_point(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        # At exactly the first point should also return None (before first point condition)
        assert env.value_at(1.0) is 10.0

    def test_value_at_after_last_point(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        env.add(time=2.0, value=20.0, type="linear")
        assert env.value_at(3.0) == 20.0  # Should return last point's value

    def test_value_at_exactly_last_point(self):
        env = Envelope(duration=5.0)
        env.add(time=2.0, value=20.0, type="step")
        assert env.value_at(2.0) == 20.0  # At last point should return value


class TestStepInterpolation:
    """Tests for step interpolation"""

    @pytest.fixture
    def step_envelope(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        env.add(time=2.0, value=20.0, type="step")
        env.add(time=3.0, value=30.0, type="step")
        return env

    def test_step_interpolation_between_points(self, step_envelope):
        # Step interpolation should return the left point's value
        assert step_envelope.value_at(1.5) == 10.0
        assert step_envelope.value_at(2.5) == 20.0

    def test_step_interpolation_at_point_boundaries(self, step_envelope):
        # At exact point times (after first point)
        assert step_envelope.value_at(1.1) == 10.0
        assert step_envelope.value_at(2.1) == 20.0

    def test_mixed_interpolation_step(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="step")
        env.add(time=2.0, value=20.0, type="linear")
        # For interval (1.0-2.0), uses left point's interpolation type (step)
        assert env.value_at(1.5) == 10.0


class TestLinearInterpolation:
    """Tests for linear interpolation"""

    def test_linear_interpolation_numeric(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="linear")
        env.add(time=3.0, value=30.0, type="step")

        # Midpoint should be average
        assert env.value_at(2.0) == 20.0

        # Quarter point
        assert env.value_at(1.5) == 15.0

        # Three-quarter point
        assert env.value_at(2.5) == 25.0

    def test_linear_interpolation_tuple_values(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=(0, 0, 0), type="linear")
        env.add(time=3.0, value=(10, 20, 30), type="linear")

        assert env.value_at(2.0) == (5, 10, 15)
        assert env.value_at(1.5) == (2.5, 5, 7.5)

    def test_linear_interpolation_list_values(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=[0, 0, 0], type="linear")
        env.add(time=3.0, value=[10, 20, 30], type="linear")

        result = env.value_at(2.0)
        assert isinstance(result, list)
        assert result == [5, 10, 15]

    def test_mixed_interpolation_linear(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0, type="linear")
        env.add(time=3.0, value=30.0, type="step")
        # Uses left point's interpolation type (linear)
        assert env.value_at(2.0) == 20.0

    def test_linear_interpolation_unsupported_type(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value="string", type="linear")
        env.add(time=3.0, value="another", type="linear")

        with pytest.raises(TypeError, match="Linear interpolation not supported"):
            env.value_at(2.0)


class TestInterpolationFunctions:
    """Tests for standalone interpolation functions"""

    def test_interpolate_linear_numeric(self):
        result = _interpolate_linear(0.0, 0, 2.0, 10, 1.0)
        assert result == 5.0

    def test_interpolate_linear_tuple(self):
        result = _interpolate_linear(0.0, (0, 0), 2.0, (10, 20), 1.0)
        assert result == (5.0, 10.0)
        assert isinstance(result, tuple)

    def test_interpolate_linear_list(self):
        result = _interpolate_linear(0.0, [0, 0], 2.0, [10, 20], 1.0)
        assert result == [5.0, 10.0]
        assert isinstance(result, list)

    def test_interpolate_linear_mismatched_lengths(self):
        with pytest.raises(TypeError):
            _interpolate_linear(0.0, (0, 0), 2.0, (10, 20, 30), 1.0)

    def test_interpolate_linear_unsupported_type(self):
        class CustomClass:
            pass

        with pytest.raises(TypeError, match="Linear interpolation not supported"):
            _interpolate_linear(0.0, CustomClass(), 2.0, CustomClass(), 1.0)


class TestComplexScenarios:
    """Tests for more complex envelope scenarios"""

    def test_multiple_interpolation_types(self):
        env = Envelope(duration=10.0)
        env.add(time=1.0, value=10.0, type="step")
        env.add(time=3.0, value=30.0, type="linear")
        env.add(time=5.0, value=50.0, type="step")
        env.add(time=7.0, value=70.0, type="linear")

        # Step interval
        assert env.value_at(2.0) == 10.0

        # Linear interval
        assert env.value_at(4.0) == 40.0

        # Step interval
        assert env.value_at(6.0) == 50.0

    def test_value_at_after_duration(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0)
        env.add(time=3.0, value=30.0)

        # Even if t > duration, should still interpolate
        assert env.value_at(4.0) == 30.0  # After last point

    def test_string_representation(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0)
        env.add(time=2.0, value=20.0)

        assert str(env) == "Envelope(duration=5.0, points=2)"


class TestEdgeCases:
    """Tests for edge cases"""

    def test_single_point_envelope(self):
        env = Envelope(duration=5.0)
        env.add(time=2.0, value=42.0, type="step")

        assert env.value_at(2.1) == 42.0
        assert env.value_at(3.0) == 42.0

    def test_negative_time_query(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0)

        # Negative time should return None (before first point)
        assert env.value_at(-1.0) is None

    def test_very_large_time_query(self):
        env = Envelope(duration=5.0)
        env.add(time=1.0, value=10.0)
        env.add(time=2.0, value=20.0)

        # Very large time should return last point's value
        assert env.value_at(1000.0) == 20.0

    def test_points_with_same_time(self):
        env = Envelope(duration=5.0)
        env.add(time=2.0, value=20.0)
        env.add(time=2.0, value=30.0)

        # Should handle duplicate times (last one will be after sort)
        # The interpolation behavior might be undefined, but shouldn't crash
        assert env.value_at(2.1) is not None

class TestEnvelopeReverse:
    """Tests for Envelope class initialization"""
    def test_reverse(self):
        """Fixture providing a sample envelope for tests"""
        env = Envelope(duration=10.0)
        env.add(time=1.0, value=10, type="step")
        env.add(time=5.0, value=20, type="linear")
        env.add(time=6.0, value=40, type="step")
        env.reverse()
        assert env.value_at(4.0) == 40.0
        assert env.value_at(5.0) == 50.0
        assert env.value_at(3.0) == 10.0


@pytest.fixture
def sample_envelope():
    """Fixture providing a sample envelope for tests"""
    env = Envelope(duration=5.0)
    env.add(time=1.0, value=10.0, type="step")
    env.add(time=2.0, value=20.0, type="linear")
    env.add(time=4.0, value=40.0, type="step")
    return env


def test_with_fixture(sample_envelope):
    """Example test using fixture"""
    assert sample_envelope.dur == 5.0
    assert len(sample_envelope.data) == 3
    assert sample_envelope.value_at(1.5) == 10.0  # step
    assert sample_envelope.value_at(3.0) == 30.0  # linear
    assert sample_envelope.value_at(5.0) == 40.0  # after last