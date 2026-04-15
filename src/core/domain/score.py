# score.py

from typing import Optional, Dict, Any

from core.domain.context import Context, wrap_in_envelopes
from core.domain.part import Part


class Score:
    """
    Structural root of a musical piece.

    Responsibilities:
        • Own the root Context (global musical defaults)
        • Own the top-level Part (musical structure)
        • Ensure the Part inherits the root Context

    Notes:
        - Score is not a Part and does not participate in the musical tree.
        - Score has no duration or performer semantics.
        - Score simply binds the global context to the structural root Part.
    """

    def __init__(self, values: Dict[str, Any] = None, part: Optional[Part] = None):
        # Create the root context with envelope-wrapped defaults
        self.context: Context = wrap_in_envelopes(values or {})

        # Top-level musical structure (Part or Container)
        self.part: Optional[Part] = part

        # Attach the root context to the part if provided
        if part is not None:
            part.context.parent = self.context

    def set_part(self, part: Part) -> None:
        """
        Replace the top-level Part and attach the Score's root context to it.
        """
        part.context.parent = self.context
        self.part = part

    def __repr__(self) -> str:
        return f"Score(context={self.context}, part={self.part})"
