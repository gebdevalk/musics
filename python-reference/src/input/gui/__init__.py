"""
GUI module for Dear PyGui windows.
"""

from .window_base import (
    BaseWindow,
    EmptyWindow,
    create_empty_window_simple,
    simplest_empty_window
)

from .window_sliders import (
    SliderWindow,
    create_slider_window,
    simple_slider_window
)

from .window_builder import (
    StepByStepApp,
    WindowTemplate
)

__all__ = [
    # Base
    'BaseWindow',
    'EmptyWindow',
    'create_empty_window_simple',
    'simplest_empty_window',

    # Sliders
    'SliderWindow',
    'create_slider_window',
    'simple_slider_window',

    # Builders
    'StepByStepApp',
    'WindowTemplate',
]

