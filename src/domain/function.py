# function.py


def _reversed_from(self, other: 'Envelope[T]') -> 'Envelope[T]':
    """Create this envelope as the reverse of another."""
    if not other._points:
        return self

    # Copy and reverse points
    self._points = [p.copy() for p in reversed(other._points)]
    duration = other.dur

    # Reverse times in place
    for p in self._points:
        p.reverse_time(duration)

    # Transform interpolations following the FXXX -> XXXF pattern
    if len(self._points) > 1:
        # Store original interpolations
        orig_ips = [p.ip for p in self._points]

        # First point becomes FIXED
        self._points[0].ip = IP.FIXED

        # Remaining points get reversed IP of the previous original point
        for i in range(1, len(self._points)):
            self._points[i].ip = orig_ips[i - 1].reversed

        # Fix adjacent FIXED/STEP and variable types
        for i in range(len(self._points) - 1):
            current_is_fixed_step = self._points[i].ip in (IP.FIXED, IP.STEP)
            next_is_fixed_step = self._points[i + 1].ip in (IP.FIXED, IP.STEP)

            # If one is fixed/step and the other is variable, swap them
            if current_is_fixed_step != next_is_fixed_step:
                self._points[i], self._points[i + 1] = self._points[i + 1], self._points[i]

    self._value_type = other._value_type
    return self

@property
def reversed(self) -> 'IP':
    """Return the interpolation type when time is reversed."""
    reverse_map = {
        IP.EASE_IN: IP.EASE_OUT,
        IP.EASE_OUT: IP.EASE_IN,
        IP.EXPONENTIAL: IP.LOGARITHMIC,
        IP.LOGARITHMIC: IP.EXPONENTIAL,
        IP.SQUARE_ROOT: IP.CUBIC_ROOT,
        IP.CUBIC_ROOT: IP.SQUARE_ROOT,
    }
    return reverse_map.get(self, self)