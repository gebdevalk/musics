# Xenvelope_test.py
import pytest
from core.domain import Envelope, IP

class TestReversedFrom:
    """Tests specifically for the _reversed_from method."""
    
    def test_reverse_empty(self):
        """Test reversing empty envelope."""
        env = Envelope()
        result = Envelope()._reversed_from(env)
        assert result.is_empty
    
    def test_reverse_single_point(self):
        """Test reversing envelope with single point."""
        orig = Envelope().add(5.0, 100, IP.LINEAR)
        result = Envelope()._reversed_from(orig)
        
        assert len(result) == 1
        assert result[0].time == 5.0  # duration 5 - 5 = 0? Wait, let's calculate
        # Actually duration is 5.0, so 5.0 - 5.0 = 0.0
        assert result[0].time == 0.0
        assert result[0].value == 100
        assert result[0].ip == IP.FIXED  # Single point becomes FIXED
    
    def test_reverse_two_points_linear(self):
        """Test reversing two points with LINEAR interpolation."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.LINEAR)\
            .add(5.0, 50.0, IP.LINEAR)
        
        result = Envelope()._reversed_from(orig)
        
        assert len(result) == 2
        assert result[0].time == 0.0  # 5-5=0
        assert result[0].value == 50.0
        assert result[0].ip == IP.FIXED  # First becomes FIXED
        
        assert result[1].time == 5.0  # 5-0=5
        assert result[1].value == 0.0
        assert result[1].ip == IP.LINEAR  # LINEAR reversed is LINEAR
    
    def test_reverse_ease_in_out(self):
        """Test reversing envelope with EASE_IN and EASE_OUT."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.LINEAR)\
            .add(2.0, 20.0, IP.EASE_IN)\
            .add(4.0, 40.0, IP.EASE_OUT)
        
        result = Envelope()._reversed_from(orig)
        
        # Check order
        assert [p.time for p in result] == [0.0, 2.0, 4.0]  # 4-4=0, 4-2=2, 4-0=4
        assert [p.value for p in result] == [40.0, 20.0, 0.0]
        
        # Check interpolations after first pass (before adjacent fix)
        # First point should be FIXED
        assert result[0].ip == IP.FIXED
        
        # Store the ips after first pass but before adjacent fix
        # In the actual method, the adjacent fix happens inside _reversed_from
        
        # Let's verify final result matches FXXX -> XXXF pattern
        ips = [p.ip for p in result]
        # Should have FIXED at start, then reversed types
        assert ips[0] == IP.FIXED
        assert ips[1] == IP.EASE_IN  # EASE_OUT reversed is EASE_IN
        assert ips[2] == IP.EASE_OUT  # EASE_IN reversed is EASE_OUT
    
    def test_reverse_fixed_and_linear(self):
        """Test reversing envelope with mix of FIXED and LINEAR."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.FIXED)\
            .add(2.0, 20.0, IP.LINEAR)\
            .add(4.0, 40.0, IP.LINEAR)
        
        result = Envelope()._reversed_from(orig)
        
        # Check FXXX -> XXXF pattern: FIXED should move to end
        ips = [p.ip for p in result]
        
        # After reversal, the FIXED should be at the end
        assert ips[0] != IP.FIXED  # First not FIXED
        assert ips[-1] == IP.FIXED  # Last is FIXED
        
        # The LINEARs should be in reverse order with proper reversal
        linear_count = sum(1 for ip in ips if ip == IP.LINEAR)
        assert linear_count == 2  # Both LINEARs remain LINEAR
    
    def test_reverse_step_and_ease(self):
        """Test reversing envelope with STEP and EASE types."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.STEP)\
            .add(3.0, 30.0, IP.EASE_IN)\
            .add(6.0, 60.0, IP.EASE_OUT)
        
        result = Envelope()._reversed_from(orig)
        
        # STEP should behave like FIXED in reversal
        ips = [p.ip for p in result]
        
        # STEP should end up at the end (like FIXED)
        assert ips[-1] in (IP.FIXED, IP.STEP)
        
        # The EASE types should be swapped
        ease_types = [ip for ip in ips if ip in (IP.EASE_IN, IP.EASE_OUT)]
        assert IP.EASE_IN in ease_types
        assert IP.EASE_OUT in ease_types
    
    def test_adjacent_fixed_swap(self):
        """Test that adjacent FIXED and variable types are swapped."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.FIXED)\
            .add(2.0, 20.0, IP.LINEAR)\
            .add(4.0, 40.0, IP.SMOOTH)
        
        result = Envelope()._reversed_from(orig)
        
        # Get the pattern of FIXED vs variable
        is_fixed = [p.ip in (IP.FIXED, IP.STEP) for p in result]
        
        # In reversed envelope, FIXED should be isolated at the end
        # So there should be no FIXED followed by variable
        for i in range(len(is_fixed) - 1):
            if is_fixed[i]:
                assert is_fixed[i+1]  # If current is fixed, next must also be fixed
    
    def test_complex_mix(self):
        """Test reversing a complex mix of all types."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.FIXED)\
            .add(1.0, 10.0, IP.LINEAR)\
            .add(2.0, 20.0, IP.EASE_IN)\
            .add(3.0, 30.0, IP.STEP)\
            .add(4.0, 40.0, IP.EASE_OUT)\
            .add(5.0, 50.0, IP.SMOOTH)
        
        result = Envelope()._reversed_from(orig)
        
        # Check that times are properly reversed
        expected_times = [5.0 - p.time for p in reversed(orig._points)]
        expected_times.sort()  # Should be chronological
        actual_times = [p.time for p in result]
        assert actual_times == pytest.approx(expected_times)
        
        # Check that values are reversed
        expected_values = [p.value for p in reversed(orig._points)]
        actual_values = [p.value for p in result]
        assert actual_values == expected_values
        
        # Check the FXXX -> XXXF pattern: all FIXED/STEP should be at the end
        ips = [p.ip for p in result]
        fixed_step_indices = [i for i, ip in enumerate(ips) 
                             if ip in (IP.FIXED, IP.STEP)]
        
        if fixed_step_indices:
            # All fixed/step should be consecutive at the end
            assert fixed_step_indices == list(range(len(ips)-len(fixed_step_indices), len(ips)))
    
    def test_double_reversal_identity(self):
        """Test that two reversals return to original (using _reversed_from directly)."""
        orig = Envelope[float]()\
            .add(0.0, 0.0, IP.FIXED)\
            .add(1.0, 10.0, IP.LINEAR)\
            .add(2.0, 20.0, IP.EASE_IN)\
            .add(3.0, 30.0, IP.EASE_OUT)\
            .add(4.0, 40.0, IP.SMOOTH)
        
        # First reversal
        once = Envelope()._reversed_from(orig)
        # Second reversal
        twice = Envelope()._reversed_from(once)
        
        # Should match original (except maybe point objects are different)
        assert len(twice) == len(orig)
        for tp, op in zip(twice._points, orig._points):
            assert tp.time == op.time
            assert tp.value == op.value
            assert tp.ip == op.ip
    
    def test_preserve_value_type(self):
        """Test that _reversed_from preserves the value type."""
        orig = Envelope[float]().add(0.0, 1.0).add(1.0, 2.0)
        result = Envelope()._reversed_from(orig)
        
        assert result._value_type == float