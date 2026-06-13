import pytest
from experimental.utils.finite_cyclic import FiniteCyclicList


class TestFiniteCyclicListMutableSequence:

    def test_initialization(self):
        """Test various initialization methods."""
        # Empty initialization
        lst = FiniteCyclicList()
        assert len(lst) == 0
        assert lst.max_repetitions == 1
        assert list(lst) == []

        # With iterable
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=3)
        assert len(lst) == 9
        assert list(lst) == [1, 2, 3, 1, 2, 3, 1, 2, 3]

        # With generator
        lst = FiniteCyclicList(range(3), max_repetitions=2)
        assert list(lst) == [0, 1, 2, 0, 1, 2]

    def test_getitem(self):
        """Test indexing."""
        lst = FiniteCyclicList(['a', 'b', 'c'], max_repetitions=3)

        assert lst[0] == 'a'
        assert lst[1] == 'b'
        assert lst[2] == 'c'
        assert lst[3] == 'a'
        assert lst[4] == 'b'
        assert lst[5] == 'c'
        assert lst[6] == 'a'
        assert lst[7] == 'b'
        assert lst[8] == 'c'

        # Negative indices
        assert lst[-1] == 'c'
        assert lst[-2] == 'b'
        assert lst[-9] == 'a'

        # Slices
        assert lst[2:5] == ['c', 'a', 'b']
        assert lst[0:9:2] == ['a', 'c', 'b', 'a', 'c']

    def test_setitem(self):
        """Test item assignment."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        lst[0] = 10
        assert lst[0] == 10
        assert lst[3] == 10

        lst[4] = 20
        assert lst[1] == 20
        assert lst[4] == 20

        # Slice assignment
        lst[0:3] = [100, 200, 300]
        assert list(lst) == [100, 200, 300, 100, 200, 300]

    def test_delitem_not_allowed(self):
        """Test that deletion is not allowed."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        with pytest.raises(TypeError, match="Cannot delete items"):
            del lst[0]

        with pytest.raises(TypeError):
            del lst[1:3]

    def test_insert(self):
        """Test insert method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        # Insert at beginning
        lst.insert(0, 0)
        assert len(lst) == 8
        assert lst._base_list == [0, 1, 2, 3]
        assert list(lst)[:4] == [0, 1, 2, 3]

        # Insert at end
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)
        lst.insert(6, 4)  # After last element
        assert lst._base_list == [1, 2, 3, 4]

        # Insert with negative index
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)
        lst.insert(-1, 9)
        assert lst._base_list == [1, 2, 9, 3]

    def test_append(self):
        """Test append method."""
        lst = FiniteCyclicList([1, 2], max_repetitions=3)
        lst.append(3)

        assert len(lst) == 9
        assert lst._base_list == [1, 2, 3]
        assert list(lst) == [1, 2, 3, 1, 2, 3, 1, 2, 3]

    def test_extend(self):
        """Test extend method."""
        lst = FiniteCyclicList([1, 2], max_repetitions=2)
        lst.extend([3, 4])

        assert len(lst) == 8
        assert lst._base_list == [1, 2, 3, 4]
        assert list(lst) == [1, 2, 3, 4, 1, 2, 3, 4]

    def test_pop(self):
        """Test pop method."""
        lst = FiniteCyclicList([1, 2, 3, 4], max_repetitions=2)

        # Pop last
        value = lst.pop()
        assert value == 4
        assert lst._base_list == [1, 2, 3]
        assert len(lst) == 6

        # Pop specific index
        lst = FiniteCyclicList([1, 2, 3, 4], max_repetitions=2)
        value = lst.pop(5)  # Should pop index 1 in base
        assert value == 2
        assert lst._base_list == [1, 3, 4]

    def test_remove(self):
        """Test remove method."""
        lst = FiniteCyclicList([1, 2, 3, 1, 2], max_repetitions=2)

        lst.remove(2)
        assert lst._base_list == [1, 3, 1, 2]
        assert len(lst) == 8

        with pytest.raises(ValueError):
            lst.remove(99)

    def test_clear(self):
        """Test clear method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=5)
        lst.clear()

        assert len(lst) == 0
        assert lst._base_list == []
        assert list(lst) == []

    def test_contains(self):
        """Test __contains__ method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=3)

        assert 1 in lst
        assert 2 in lst
        assert 3 in lst
        assert 4 not in lst

    def test_index(self):
        """Test index method."""
        lst = FiniteCyclicList([1, 2, 3, 1], max_repetitions=2)

        assert lst.index(1) == 0
        assert lst.index(1, 1) == 3
        assert lst.index(2) == 1
        assert lst.index(3) == 2

        with pytest.raises(ValueError):
            lst.index(4)

    def test_count(self):
        """Test count method."""
        lst = FiniteCyclicList([1, 2, 1, 3], max_repetitions=3)

        assert lst.count(1) == 6  # 2 occurrences * 3 repetitions
        assert lst.count(2) == 3
        assert lst.count(3) == 3
        assert lst.count(4) == 0

    def test_reversed(self):
        """Test __reversed__ method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        reversed_list = list(reversed(lst))
        assert reversed_list == [3, 2, 1, 3, 2, 1]

    def test_get_cycle(self):
        """Test get_cycle method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=3)

        assert lst.get_cycle(0) == [1, 2, 3]
        assert lst.get_cycle(1) == [1, 2, 3]
        assert lst.get_cycle(2) == [1, 2, 3]

        with pytest.raises(IndexError):
            lst.get_cycle(3)

    def test_get_cycle_range(self):
        """Test get_cycle_range method."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=4)

        assert lst.get_cycle_range(0, 2) == [1, 2, 3, 1, 2, 3]
        assert lst.get_cycle_range(1, 3) == [1, 2, 3, 1, 2, 3]
        assert lst.get_cycle_range(2, 5) == [1, 2, 3, 1, 2, 3]
        assert lst.get_cycle_range(0, 0) == []

    def test_mutable_sequence_protocol(self):
        """Test that we properly implement MutableSequence protocol."""
        from collections.abc import MutableSequence

        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        # Should be recognized as a MutableSequence
        assert isinstance(lst, MutableSequence)

        # Should have all required methods
        required_methods = [
            '__getitem__', '__setitem__', '__delitem__', '__len__', 'insert'
        ]
        for method in required_methods:
            assert hasattr(lst, method)

    def test_type_validation(self):
        """Test type validation for max_repetitions."""
        with pytest.raises(TypeError):
            FiniteCyclicList([1, 2, 3], max_repetitions="3")

        with pytest.raises(ValueError):
            FiniteCyclicList([1, 2, 3], max_repetitions=-1)