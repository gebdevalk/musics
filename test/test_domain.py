# test_domain.py

import pytest

# Adjust these imports to match your actual module paths
from core.domain.container import Container
from core.domain.context import Context, wrap_in_envelopes
from core.domain.leafs import Leaf
from core.domain.point_envelope import Envelope, IP
from core.domain.score import Score


# ---------------------------------------------------------
# 1. ROOT + CONTEXT TESTS
# ---------------------------------------------------------

def test_root_wraps_defaults_in_envelopes():
    root = wrap_in_envelopes(values={"volume": 0.5})

    assert "volume" in root._state
    env = root._state["volume"]

    assert isinstance(env, Envelope)
    assert env.get(0.0) == 0.5
    assert env.get(10.0) == 0.5


# def test_root_debug_points():
#     root = ROOT(values={"volume": 0.5})
#     env = root._state["volume"]
#     print("POINTS:", env.points)
#     assert False

def test_context_inheritance_and_shadowing():
    root = wrap_in_envelopes(values={"volume": 0.5})
    child = Context(parent=root)

    # Inherit from root
    assert child.value("volume", 0.0) == 0.5

    # Override in child
    env = Envelope().append(0.0, 0.9)
    child.set("volume", env)

    assert child.value("volume", 0.0) == 0.9


# ---------------------------------------------------------
# 2. CONTAINER FACTORY METHOD TEST
# ---------------------------------------------------------

def test_container_factory_assigns_context():
    root = wrap_in_envelopes(values={"tempo": 120})
    c = Container.with_parent_context(root)

    assert c.context.parent is root
    assert c.context.value("tempo", 0.0) == 120


# ---------------------------------------------------------
# 3. LEAF CONTEXT INHERITANCE TEST
# ---------------------------------------------------------

def test_leaf_inherits_context():
    from core.domain.container import Container
    from core.domain.leafs import Leaf
    from tools.ratio import Ratio

    root = wrap_in_envelopes(values={"timbre": 5})
    c = Container.with_parent_context(root)

    leaf = Leaf(
        pitches=[60],
        duration=Ratio(1, 1),
        context=c.context
    )

    assert leaf.context.value("timbre", 0.0) == 5



# ---------------------------------------------------------
# 4. SCORE TESTS
# ---------------------------------------------------------

def test_score_attaches_root_context_to_part():
    root = wrap_in_envelopes({"volume": 0.7})
    score = Score(context = root)
    top = Container()

    score.set_part(top)

    assert score.part.context.parent is score.context
    assert score.part.context.value("volume", 0.0) == 0.7


# ---------------------------------------------------------
# 5. FULL INTEGRATION TEST
# ---------------------------------------------------------

def test_full_context_chain_integration():
    """
    Score(ROOT) → Container → Container → Leaf
    Tests inheritance, shadowing, and correct context propagation.
    """

    score = Score(context = wrap_in_envelopes({"volume": 0.5}))

    # Top-level container
    top = Container()
    score.set_part(top)

    # Child container
    child = Container.with_parent_context(top.context)
    top.append(child)

    # Leaf inside child
    leaf = Leaf(pitch=60, duration=1.0, context=child.context)
    child.append(leaf)

    # Inherit from root
    assert leaf.context.value("volume", 0.0) == 0.5

    # Override in child
    env = Envelope().append(0.0, 0.9)
    child.context.set("volume", env)

    assert leaf.context.value("volume", 0.0) == 0.9


# ---------------------------------------------------------
# 6. ENVELOPE BASIC TESTS (OPTIONAL BUT USEFUL)
# ---------------------------------------------------------

def test_envelope_constant_behavior():
    env = Envelope().append(0.0, 10.0, IP.FIXED)

    assert env.get(0.0) == 10.0
    assert env.get(5.0) == 10.0
    assert env.get(999.0) == 10.0


def test_envelope_linear_interpolation():
    env = Envelope()
    env.append(0.0, 0.0, IP.LINEAR_UP)
    env.append(1.0, 10.0, IP.LINEAR_UP)

    assert env.get(0.0) == 0.0
    assert env.get(0.5) == pytest.approx(5.0)
    assert env.get(1.0) == 10.0
