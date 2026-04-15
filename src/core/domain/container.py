# container.py

from enum import Enum, auto

from core.domain.context import Context
from core.domain.part import Part, Identifiable


class Instruction(Enum):
    SEQ = auto()
    PAR = auto()


class Container(Identifiable, Part):
    """
    A structural AST node that groups musical Parts into a composite unit.

    Container does not represent musical content itself; it defines how its
    children are organized and interpreted by the performer. Each Container
    owns a Context, an Instruction (e.g., SEQ or PAR), an optional role, and
    a private list of child Parts.

    Key semantics:
        • Structure only — Container is a composite node, not a musical value.
        • Context propagation — children inherit this Container's Context when
          appended or extended.
        • Identity — every Container has a compact, stable id (via Identifiable).
        • Instruction — determines how the performer interprets the children
          (e.g., sequential vs. parallel execution).
        • Children are private — mutation must go through append()/extend() to
          preserve context and structural invariants.

    Container is the base class for Sequential and Parallel, which refine the
    interpretation of child ordering and duration behavior.
    """

    __slots__ = ("instruction", "role", "_children")

    def __init__(
            self,
            *,
            context: Context | None = None,
            instruction: Instruction = Instruction.SEQ,
            role: str | None = None,
    ):
        Identifiable.__init__(self)
        super().__init__(context=context)

        if self.context is None:
            self.context = Context()

        self.instruction = instruction
        self.role = role
        self._children: list = []

    def append(self, part: Part):
        """
        Add a Part to this Container.

        Context rules:
            • If `part` is a leaf (non-Container), its context becomes this
              container's context.
            • If `part` is a Container, its own Context remains, but its
              Context.parent is set to this container's context.
        """
        if isinstance(part, Container):
            # Container child: keep its own context, but attach parent
            part.context.parent = self.context
        else:
            # Leaf child: inherit this container's context directly
            part.context = self.context
        self._children.append(part)

    def extend(self, container: "Container"):
        """
        Add all children from another container of the same type T.
        Each child inherits this container's context.
        """
        for child in container._children:
            self._children.append(child)

    def get_child(self, idx: int) -> Part:
        return self._children[idx]

    def __iter__(self):
        return iter(self._children)


class Sequential(Container):
    """
    A Sequential container is a Container whose instruction is Instruction.SEQ.
    It represents an ordered sequence of child Parts.

    Unique semantics:
    - Children are interpreted strictly in order.
    - Duration is the sum of the children's durations.
    - Performer traversal advances the musical offset cumulatively.

    The optional `role` string labels the sequence (e.g. "phrase", "motif")
    without affecting timing.
    """
    def append(self, part: Part):
        super().append(part)
        self.duration += part.duration



class Parallel(Container):
    """
    A Parallel container is a Container whose instruction is Instruction.PAR.
    It represents child Parts that begin simultaneously and run concurrently.

    Unique semantics:
    - All children start at the same musical time.
    - Duration is the maximum of the children's durations.
    - Performer traversal forks into independent voices or layers.

    The optional `role` string labels the parallel group (e.g. "voice",
    "layer", "harmony") without affecting timing.
    """

    def append(self, part: Part):
        super().append(part)
        self.duration = max(self.duration, part.duration)


# =========================
# Algorithm
# =========================

class Algorithm(Identifiable, Part):
    """
    A symbolic AST leaf that requests algorithmic generation at performer time.

    Algorithm does not contain musical content itself. Instead, it encodes an
    algorithm name and optional arguments that the performer resolves into one
    or more concrete Parts during traversal. This allows declarative, symbolic
    specification of musical processes without embedding executable logic in
    the domain model.

    Key semantics:
        • Symbolic — Algorithm represents intent, not sound.
        • Performer-resolved — the performer looks up `algorithm` in the
          algorithm registry and expands this node into concrete Parts.
        • Context-aware — expansion occurs within the current Context, allowing
          tempo, offset, and environment to influence the generated material.
        • Pure data — Algorithm stores only a name and argument dictionary; it
          performs no computation itself.

    Algorithm is a leaf in the structural tree: it has no children until the
    performer expands it.
    """
    __slots__ = ("algorithm", "args")

    def __init__(self, *, algorithm: str, args: dict | None = None, context=None):
        Identifiable.__init__(self)
        Part.__init__(self, context=context)
        self.algorithm = algorithm
        self.args = args or {}