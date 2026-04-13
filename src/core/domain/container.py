# container.py
from dataclasses import dataclass, field
from typing import List

from core.domain.context import Context
from core.domain.part import Part


@dataclass
class Container(Part):
    """
    Structural composite node.
    Holds children (Parts) and a Context (via Part.parent).
    """
    @classmethod
    def with_context(cls, parent_ctx):
        ctx = Context(parent=parent_ctx)
        return cls(context=ctx)

    children: List[Part] = field(default_factory=list)

    def add(self, part: Part):
        # Child inherits this container's context unless overridden
        if part.context is None:
            part.context = self.context
        self.children.append(part)

    def get_child(self, idx: int) -> Part:
        return self.children[idx]

    def __iter__(self):
        return iter(self.children)



@dataclass
class Parallel:
    children: List

    def __iter__(self):
        return iter(self.children)

    def __len__(self):
        return len(self.children)
