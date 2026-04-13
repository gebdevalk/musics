# score.py
from typing import Optional, Dict, Any

from core.domain.context import Root, Context
from core.domain.part import Part


class Score:
    """
    Structural root of the musical piece.
    Holds:
      - the Root Context (global defaults)
      - the top-level Part (musical structure)
    """

    def __init__(self, values: Dict[str, Any] = None, part: Optional[Part] = None):
        # Root context with envelope-wrapped defaults
        self.context = Root(values or {})

        # Top-level musical structure
        self.part = part

        # If a part is provided, attach the context
        if part is not None:
            part.context = self.context

    def set_part(self, part: Part):
        part.context = self.context
        self.part = part

    def __repr__(self):
        return f"Score(context={self.context}, part={self.part})"

