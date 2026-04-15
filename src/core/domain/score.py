# score.py

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
    __slots__ = ('context', 'part')

    def __init__(self, context=None, part=None):
        self.context = context
        self.part = part
        if part is not None:
            part.context = self.context

    # def set_part(self, part):
    #     part.context = self.context
    #     self.part = part

    def set_part(self, part: Part) -> None:
        """
        Replace the top-level Part and attach the Score's root context to it.
        """
        part.context.parent = self.context
        self.part = part

    def __repr__(self) -> str:
        return f"Score(context={self.context}, part={self.part})"
