
"""
filter_graph.py

A small, composable FilterGraph for symbolic musical material.

- Nodes wrap filters (including recurrence filters).
- Edges define dataflow between nodes.
- Graphs can branch, merge, and (optionally) have feedback.
- Execution is pull-based: you ask the graph for output at a sink node.

This is meant to sit alongside:
- filters.py
- advanced_filters.py
- z_filters.py (recurrence filters)
"""

from __future__ import annotations
from typing import Callable, Iterable, List, Dict, Any, TypeVar, Optional, Set

T = TypeVar("T")
Filter = Callable[[Iterable[T]], List[T]]


# ------------------------------------------------------------
# Node: wraps a filter and its incoming edges
# ------------------------------------------------------------

class Node:
    """
    A Node represents a single musical transformation.

    - It has zero or more input nodes.
    - It has exactly one filter (callable).
    - When evaluated, it:
        * pulls data from its inputs,
        * combines them (by default: first input or provided combiner),
        * applies its filter,
        * returns a new list.
    """

    def __init__(
        self,
        name: str,
        filter_fn: Filter,
        inputs: Optional[List[Node]] = None,
        combiner: Optional[Callable[[List[List[T]]], List[T]]] = None,
    ):
        self.name = name
        self.filter_fn = filter_fn
        self.inputs: List[Node] = inputs or []
        # combiner takes a list of input sequences and returns a single sequence
        self.combiner = combiner or self._default_combiner

    def _default_combiner(self, inputs: List[List[T]]) -> List[T]:
        """
        Default behavior:
        - no inputs: empty list
        - one input: pass-through
        - multiple inputs: concatenate
        """
        if not inputs:
            return []
        if len(inputs) == 1:
            return inputs[0]
        out: List[T] = []
        for seq in inputs:
            out.extend(seq)
        return out

    def eval(self, cache: Dict[str, List[T]]) -> List[T]:
        """
        Evaluate this node:
        - use cache to avoid recomputation in a single graph run
        - recursively evaluate inputs
        - combine inputs
        - apply filter
        """
        if self.name in cache:
            return cache[self.name]

        input_seqs = [n.eval(cache) for n in self.inputs]
        combined = self.combiner(input_seqs)
        result = self.filter_fn(combined)
        cache[self.name] = result
        return result

    def __repr__(self) -> str:
        return f"Node({self.name})"


# ------------------------------------------------------------
# FilterGraph: manages nodes and evaluation
# ------------------------------------------------------------

class FilterGraph:
    """
    A FilterGraph is a network of Nodes.

    - You add nodes with filters.
    - You connect nodes by specifying inputs.
    - You evaluate the graph by asking for the output of a sink node.

    This turns your filters into a modular, reusable musical system.
    """

    def __init__(self):
        self.nodes: Dict[str, Node] = {}

    # ---- Node management -------------------------------------------------

    def add_node(
        self,
        name: str,
        filter_fn: Filter,
        inputs: Optional[List[str]] = None,
        combiner: Optional[Callable[[List[List[T]]], List[T]]] = None,
    ) -> Node:
        """
        Create a node and register it in the graph.

        name: unique identifier
        filter_fn: a Filter (Callable[[Iterable[T]], List[T]])
        inputs: list of node names this node depends on
        combiner: optional function to combine multiple input sequences
        """
        if name in self.nodes:
            raise ValueError(f"Node {name!r} already exists")

        input_nodes = [self.nodes[n] for n in (inputs or [])]
        node = Node(name=name, filter_fn=filter_fn, inputs=input_nodes, combiner=combiner)
        self.nodes[name] = node
        return node

    def connect(self, node_name: str, input_names: List[str]) -> None:
        """
        Set the inputs of an existing node.
        """
        node = self.nodes[node_name]
        node.inputs = [self.nodes[n] for n in input_names]

    # ---- Evaluation ------------------------------------------------------

    def eval(self, sink: str, source_data: List[T]) -> List[T]:
        """
        Evaluate the graph at a given sink node.

        - You provide source_data as the "raw" input.
        - The graph must have a designated source node that just returns source_data.
        - All other nodes pull from their inputs.

        Typical pattern:
            - create a 'source' node whose filter is identity
            - other nodes depend on 'source' or each other
        """
        if sink not in self.nodes:
            raise ValueError(f"Unknown sink node {sink!r}")

        cache: Dict[str, List[T]] = {}

        # We inject source_data into a special node if present.
        # Convention: a node named "source" (or any you choose) can be used.
        if "source" in self.nodes:
            cache["source"] = list(source_data)

        return self.nodes[sink].eval(cache)

    # ---- Introspection / safety -----------------------------------------

    def topological_order(self) -> List[str]:
        """
        Return a topological ordering of nodes (if acyclic).
        Raises ValueError if a cycle is detected.

        This is useful if you want to ensure no feedback loops,
        or to debug graph structure.
        """
        visited: Set[str] = set()
        temp: Set[str] = set()
        order: List[str] = []

        def visit(n: str):
            if n in temp:
                raise ValueError("Cycle detected in graph")
            if n in visited:
                return
            temp.add(n)
            for inp in self.nodes[n].inputs:
                visit(inp.name)
            temp.remove(n)
            visited.add(n)
            order.append(n)

        for name in self.nodes:
            visit(name)

        return order

    def __repr__(self) -> str:
        return f"FilterGraph(nodes={list(self.nodes.keys())})"


# ------------------------------------------------------------
# Example usage with your existing filters
# ------------------------------------------------------------

if __name__ == "__main__":
    # These would normally come from filters.py / advanced_filters.py / z_filters.py
    from typing import Iterable, List

    def low_pass(limit: float, key=lambda x: x) -> Filter:
        def apply(seq: Iterable[T]) -> List[T]:
            return [x for x in seq if key(x) <= limit]
        return apply

    def high_pass(limit: float, key=lambda x: x) -> Filter:
        def apply(seq: Iterable[T]) -> List[T]:
            return [x for x in seq if key(x) >= limit]
        return apply

    def smooth(alpha: float, key=lambda x: x, set_value=None) -> Filter:
        # simple numeric smoother for demo
        def apply(seq: Iterable[float]) -> List[float]:
            seq = list(seq)
            if not seq:
                return []
            out = [seq[0]]
            for x in seq[1:]:
                out.append((1 - alpha) * x + alpha * out[-1])
            return out
        return apply

    # Build a small graph:
    g = FilterGraph()

    # Source node: identity filter, will get data injected via cache["source"]
    g.add_node("source", filter_fn=lambda xs: list(xs))

    # Node that low-passes the source
    g.add_node("lp", filter_fn=low_pass(70), inputs=["source"])

    # Node that high-passes the source
    g.add_node("hp", filter_fn=high_pass(60), inputs=["source"])

    # Node that smooths the low-passed result
    g.add_node("smooth_lp", filter_fn=smooth(0.5), inputs=["lp"])

    # Node that combines hp and smooth_lp by concatenation (default combiner)
    g.add_node("combined", filter_fn=lambda xs: list(xs), inputs=["hp", "smooth_lp"])

    data = [55, 60, 62, 65, 72, 75, 58, 61]

    # Evaluate at different sinks
    print("LP:", g.eval("lp", data))
    print("HP:", g.eval("hp", data))
    print("Smooth LP:", g.eval("smooth_lp", data))
    print("Combined:", g.eval("combined", data))
