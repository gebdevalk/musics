# test_meta.py

import pytest
from core.domain.meta import Meta
from core.domain.point_envelope import Envelope
from tools.ratio import Ratio


# ------------------------------------------------------------------
# Fixtures
# ------------------------------------------------------------------

@pytest.fixture
def root():
    return Meta(parent=None, **{
        "tempo": 120,
        "volume": 80,
        "instrument": 0,
    })

@pytest.fixture
def child(root):
    return Meta(parent=root, **{
        "volume": 60,       # overrides root
        "articulation": 0.5,
    })

@pytest.fixture
def grandchild(child):
    return Meta(parent=child, **{
        "accent": 0.75,     # only at this level
    })

@pytest.fixture
def envelope_meta():
    env = Envelope()
    empty_env = Envelope()
    return Meta(parent=None, **{
        "volume": env,
        "articulation": empty_env,  # empty envelope — should fall through
    })

@pytest.fixture
def chain(root, child, grandchild):
    """Convenience: returns the full three-level chain."""
    return root, child, grandchild


# ------------------------------------------------------------------
# Construction
# ------------------------------------------------------------------

class TestConstruction:

    def test_empty_meta(self):
        m = Meta()
        assert len(m) == 0
        assert m.parent is None

    def test_with_values(self, root):
        assert root["tempo"] == 120

    def test_parent_set(self, child, root):
        assert child.parent is root

    def test_invalid_parent_raises(self):
        with pytest.raises(TypeError, match="meta instance"):
            Meta(parent="not_a_meta")

    def test_parent_setter_validates(self, root):
        m = Meta()
        with pytest.raises(TypeError):
            m.parent = 42

    def test_parent_setter_accepts_none(self, root):
        root.parent = None
        assert root.parent is None

    def test_parent_setter_accepts_meta(self, root):
        m = Meta()
        m.parent = root
        assert m.parent is root


# ------------------------------------------------------------------
# Core lookup — own keys
# ------------------------------------------------------------------

class TestOwnLookup:

    def test_getitem_own_key(self, root):
        assert root["tempo"] == 120

    def test_get_own_key(self, root):
        assert root.get("tempo") == 120

    def test_contains_own_key(self, root):
        assert "tempo" in root

    def test_missing_key_raises(self, root):
        with pytest.raises(KeyError):
            _ = root["missing"]

    def test_get_missing_returns_default(self, root):
        assert root.get("missing") is None
        assert root.get("missing", 99) == 99


# ------------------------------------------------------------------
# Parent chain lookup
# ------------------------------------------------------------------

class TestChainLookup:

    def test_child_finds_parent_key(self, child, root):
        """tempo is only in root — child should find it."""
        assert child["tempo"] == 120

    def test_child_overrides_parent_key(self, child):
        """volume is in both — child's value wins."""
        assert child["volume"] == 60

    def test_grandchild_finds_root_key(self, grandchild):
        """tempo is only in root — grandchild traverses two levels."""
        assert grandchild["tempo"] == 120

    def test_grandchild_finds_child_key(self, grandchild):
        """articulation is only in child."""
        assert grandchild["articulation"] == 0.5

    def test_grandchild_own_key(self, grandchild):
        assert grandchild["accent"] == 0.75

    def test_grandchild_overrides_skip_parent(self, grandchild, root):
        """volume: root=80, child=60 — grandchild inherits child's 60."""
        assert grandchild["volume"] == 60

    def test_missing_in_chain_raises(self, grandchild):
        with pytest.raises(KeyError):
            _ = grandchild["nonexistent"]

    def test_get_falls_through_chain(self, grandchild):
        assert grandchild.get("tempo") == 120

    def test_contains_traverses_chain(self, grandchild):
        assert "tempo" in grandchild
        assert "articulation" in grandchild
        assert "accent" in grandchild

    def test_contains_missing_in_chain(self, grandchild):
        assert "nonexistent" not in grandchild


# ------------------------------------------------------------------
# Envelope handling
# ------------------------------------------------------------------

class TestEnvelopeLookup:

    def test_non_empty_envelope_is_returned(self):
        env = Envelope()
        env.add(Ratio(0, 1),0.5) # add a point so it's non-empty
        m = Meta(parent=None, **{"volume": env})
        assert m["volume"] is env

    def test_empty_envelope_falls_through_to_parent(self):
        """An empty Envelope in child should fall through to parent's value."""
        parent = Meta(parent=None, **{"volume": 80})
        empty_env = Envelope()
        child = Meta(parent=parent, **{"volume": empty_env})
        assert child["volume"] == 80

    def test_empty_envelope_not_in_contains(self):
        parent = Meta(parent=None, **{"volume": 80})
        empty_env = Envelope()
        child = Meta(parent=parent, **{"volume": empty_env})
        # contains should also fall through
        assert "volume" in child   # found in parent

    def test_empty_envelope_no_parent_raises(self):
        empty_env = Envelope()
        m = Meta(parent=None, **{"volume": empty_env})
        with pytest.raises(KeyError):
            _ = m["volume"]

    def test_non_empty_envelope_blocks_parent(self):
        env = Envelope()
        env.add(Ratio(0, 1),0.9) # add a point so it's non-empty
        parent = Meta(parent=None, **{"articulation": 0.5})
        child = Meta(parent=parent, **{"articulation": env})
        result = child["articulation"]
        assert result is env        # child's envelope wins, not parent's scalar


# ------------------------------------------------------------------
# Depth and introspection
# ------------------------------------------------------------------

class TestIntrospection:

    def test_depth_root(self, root):
        assert root.depth() == 0

    def test_depth_child(self, child):
        assert child.depth() == 1

    def test_depth_grandchild(self, grandchild):
        assert grandchild.depth() == 2

    def test_all_keys_root(self, root):
        assert root.all_keys() == {"tempo", "volume", "instrument"}

    def test_all_keys_child(self, child, root):
        keys = child.all_keys()
        assert "tempo" in keys         # from root
        assert "volume" in keys        # own + root
        assert "articulation" in keys  # own

    def test_all_keys_grandchild(self, grandchild):
        keys = grandchild.all_keys()
        assert {"tempo", "volume", "instrument", "articulation", "accent"}.issubset(keys)

    def test_resolved_root(self, root):
        r = root.resolved()
        assert r["tempo"] == 120
        assert r["volume"] == 80

    def test_resolved_child_overrides(self, child):
        r = child.resolved()
        assert r["volume"] == 60      # child wins
        assert r["tempo"] == 120      # from root

    def test_resolved_grandchild_full(self, grandchild):
        r = grandchild.resolved()
        assert r["tempo"] == 120
        assert r["volume"] == 60
        assert r["articulation"] == 0.5
        assert r["accent"] == 0.75


# ------------------------------------------------------------------
# Detached and re-parented nodes
# ------------------------------------------------------------------

class TestReparenting:

    def test_detach_loses_parent_keys(self, child):
        child.parent = None
        with pytest.raises(KeyError):
            _ = child["tempo"]   # was only in root

    def test_reparent_gains_new_keys(self, root):
        other_root = Meta(parent=None, **{"key": "G"})
        m = Meta(parent=other_root, **{"tempo": 100})
        assert m["key"] == "G"
        m.parent = root
        with pytest.raises(KeyError):
            _ = m["key"]         # no longer reachable
        assert m["volume"] == 80  # now reachable via root


# ------------------------------------------------------------------
# Repr
# ------------------------------------------------------------------

class TestRepr:

    def test_repr_no_parent(self, root):
        r = repr(root)
        assert "Meta" in r
        assert "parent" not in r

    def test_repr_with_parent(self, child):
        r = repr(child)
        assert "parent=Meta" in r
