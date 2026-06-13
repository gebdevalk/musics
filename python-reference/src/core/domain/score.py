"""Score — the root Composite of a musical piece.

A Score is simply a Composite with type="SCORE".
It holds the root Context and the top-level Part as its children.
"""

from core.domain.parts import Composite, Part
from core.domain.context import Context


def make_score(root_ctx: Context, part: Part | None = None,
               id: str = "score") -> Composite:
    """Create a Score (Composite with type='SCORE').

    The part's context.parent is set to root_ctx so that lookups
    fall through to the global defaults.
    """
    score = Composite(type="SCORE", id=id, context=root_ctx, children=[])
    if part is not None:
        # Set parent context via replace (Leaf/Rest/Drum are frozen)
        # Composite context is mutable, but we handle both cases
        try:
            part.context.parent = root_ctx
        except AttributeError:
            # Frozen — use dataclasses.replace
            from dataclasses import replace
            part = replace(part, context=part.context._replace(parent=root_ctx))
        score.append(part)
    return score


# Backward-compatible alias
Score = Composite  # Score IS a Composite with type="SCORE"
