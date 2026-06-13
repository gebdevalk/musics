import pytest
from experimental.utils.finite_cyclic import FiniteCyclicList


class TestFiniteCyclicListInitialization:

    def test_default_initialization(self):
        """Test initialization with no arguments."""
        lst = FiniteCyclicList()

        assert len(lst) == 0
        assert lst.max_repetitions == 1
        assert list(lst) == []
        assert lst._total_length == 0
        assert isinstance(lst, list)  # Verify it's a subclass of list
        assert isinstance(lst, FiniteCyclicList)  # Verify it's our class

    def test_initialization_with_list(self):
        """Test initialization with a list argument."""
        lst = FiniteCyclicList([1, 2, 3, 4, 5])

        assert len(lst) == 5
        assert lst.max_repetitions == 1
        assert list(lst) == [1, 2, 3, 4, 5]
        assert lst._total_length == 5
        assert lst[0] == 1
        assert lst[4] == 5

    def test_initialization_with_tuple(self):
        """Test initialization with a tuple argument."""
        lst = FiniteCyclicList((1, 2, 3, 4, 5))

        assert len(lst) == 5
        assert list(lst) == [1, 2, 3, 4, 5]
        assert lst._total_length == 5

    def test_initialization_with_string(self):
        """Test initialization with a string argument."""
        lst = FiniteCyclicList("hello")

        assert len(lst) == 5
        assert list(lst) == ['h', 'e', 'l', 'l', 'o']
        assert lst._total_length == 5

    def test_initialization_with_range(self):
        """Test initialization with a range object."""
        lst = FiniteCyclicList(range(5))

        assert len(lst) == 5
        assert list(lst) == [0, 1, 2, 3, 4]
        assert lst._total_length == 5

    def test_initialization_with_max_repetitions(self):
        """Test initialization with max_repetitions parameter."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=5)

        assert len(lst) == 15  # 3 * 5
        assert lst.max_repetitions == 5
        assert lst._total_length == 15
        assert list(lst) == [1, 2, 3] * 5

    def test_initialization_with_max_repetitions_zero(self):
        """Test initialization with max_repetitions = 0."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=0)

        assert len(lst) == 0
        assert lst.max_repetitions == 0
        assert lst._total_length == 0
        assert list(lst) == []

    def test_initialization_with_max_repetitions_one(self):
        """Test initialization with max_repetitions = 1 (default)."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=1)

        assert len(lst) == 3
        assert lst.max_repetitions == 1
        assert lst._total_length == 3
        assert list(lst) == [1, 2, 3]

    def test_initialization_with_empty_list_and_max_repetitions(self):
        """Test initialization with empty list and custom max_repetitions."""
        lst = FiniteCyclicList([], max_repetitions=10)

        assert len(lst) == 0
        assert lst.max_repetitions == 10
        assert lst._total_length == 0
        assert list(lst) == []

    def test_initialization_with_none_and_max_repetitions(self):
        """Test initialization with None and custom max_repetitions."""
        lst = FiniteCyclicList(None, max_repetitions=5)

        assert len(lst) == 0
        assert lst.max_repetitions == 5
        assert lst._total_length == 0
        assert list(lst) == []

    def test_initialization_with_single_element(self):
        """Test initialization with a single-element list."""
        lst = FiniteCyclicList([42], max_repetitions=3)

        assert len(lst) == 3
        assert lst.max_repetitions == 3
        assert list(lst) == [42, 42, 42]
        assert lst[0] == 42
        assert lst[1] == 42
        assert lst[2] == 42

    def test_initialization_preserves_order(self):
        """Test that initialization preserves the order of elements."""
        original = [5, 2, 8, 1, 9, 3]
        lst = FiniteCyclicList(original, max_repetitions=2)

        assert list(lst)[:6] == original  # First cycle matches original
        assert list(lst)[6:] == original  # Second cycle matches original

    def test_initialization_with_large_max_repetitions(self):
        """Test initialization with a very large max_repetitions value."""
        lst = FiniteCyclicList([1, 2], max_repetitions=1000000)

        assert len(lst) == 2000000
        assert lst.max_repetitions == 1000000
        assert lst._total_length == 2000000

    def test_initialization_with_nested_structures(self):
        """Test initialization with nested data structures."""
        nested_data = [[1, 2], {'a': 1}, (3, 4), "string"]
        lst = FiniteCyclicList(nested_data, max_repetitions=2)

        assert len(lst) == 8  # 4 items * 2 repetitions
        assert lst[0] == [1, 2]
        assert lst[1] == {'a': 1}
        assert lst[2] == (3, 4)
        assert lst[3] == "string"
        assert lst[4] == [1, 2]  # Second cycle

    def test_initialization_with_mixed_types(self):
        """Test initialization with mixed data types."""
        mixed_data = [1, "two", 3.0, True, None]
        lst = FiniteCyclicList(mixed_data, max_repetitions=3)

        assert len(lst) == 15  # 5 items * 3 repetitions
        assert lst[0] == 1
        assert lst[1] == "two"
        assert lst[2] == 3.0
        assert lst[3] is True
        assert lst[4] is None

    def test_initialization_with_generator(self):
        """Test initialization with a generator expression."""
        lst = FiniteCyclicList((x ** 2 for x in range(4)), max_repetitions=2)

        assert len(lst) == 8
        assert list(lst) == [0, 1, 4, 9, 0, 1, 4, 9]

    def test_initialization_negative_max_repetitions(self):
        """Test initialization with negative max_repetitions."""
        # This might be allowed or not - document the behavior
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=-1)

        # If allowed, what should happen?
        # Currently, it would create a negative length, which might cause issues
        # This test documents the current behavior
        assert lst.max_repetitions == -1
        assert lst._total_length == -3  # This is problematic!
        # You might want to add validation in __init__ to prevent this

    def test_initialization_with_non_integer_max_repetitions(self):
        """Test initialization with non-integer max_repetitions."""
        with pytest.raises(TypeError):
            FiniteCyclicList([1, 2, 3], max_repetitions="3")

        with pytest.raises(TypeError):
            FiniteCyclicList([1, 2, 3], max_repetitions=3.5)

    def test_initialization_preserves_references(self):
        """Test that initialization preserves object references."""
        inner_list = [1, 2, 3]
        lst = FiniteCyclicList([inner_list], max_repetitions=2)

        # Modify the original inner list
        inner_list.append(4)

        # The change should be reflected in the FiniteCyclicList
        assert lst[0] == [1, 2, 3, 4]
        assert lst[1] == [1, 2, 3, 4]

    def test_initialization_with_custom_object(self):
        """Test initialization with custom objects."""

        class Point:
            def __init__(self, x, y):
                self.x = x
                self.y = y

            def __eq__(self, other):
                return self.x == other.x and self.y == other.y

        points = [Point(1, 2), Point(3, 4)]
        lst = FiniteCyclicList(points, max_repetitions=3)

        assert len(lst) == 6
        assert lst[0] == Point(1, 2)
        assert lst[1] == Point(3, 4)
        assert lst[2] == Point(1, 2)

    def test_initialization_type_inheritance(self):
        """Test that the initialized object has correct type relationships."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=2)

        assert isinstance(lst, list)
        assert isinstance(lst, FiniteCyclicList)
        assert issubclass(FiniteCyclicList, list)

    def test_initialization_multiple_instances(self):
        """Test that multiple instances don't interfere with each other."""
        lst1 = FiniteCyclicList([1, 2, 3], max_repetitions=2)
        lst2 = FiniteCyclicList(['a', 'b'], max_repetitions=3)
        lst3 = FiniteCyclicList([], max_repetitions=5)

        assert len(lst1) == 6
        assert len(lst2) == 6
        assert len(lst3) == 0

        assert list(lst1) == [1, 2, 3, 1, 2, 3]
        assert list(lst2) == ['a', 'b', 'a', 'b', 'a', 'b']
        assert list(lst3) == []

    def test_initialization_attributes(self):
        """Test that initialization sets all required attributes."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=4)

        assert hasattr(lst, 'max_repetitions')
        assert hasattr(lst, '_total_length')
        assert lst.max_repetitions == 4
        assert lst._total_length == 12

    @pytest.mark.parametrize("input_data, expected_length", [
        ([], 0),
        ([1], 1),
        ([1, 2], 2),
        (list(range(10)), 10),
        ("test", 4),
        ((1, 2, 3), 3),
    ])
    def test_initialization_various_inputs(self, input_data, expected_length):
        """Test initialization with various input types using parameterization."""
        lst = FiniteCyclicList(input_data)

        assert len(lst) == expected_length
        assert lst.max_repetitions == 1
        assert lst._total_length == expected_length

    @pytest.mark.parametrize("max_rep, expected_total", [
        (1, 3),
        (2, 6),
        (3, 9),
        (5, 15),
        (10, 30),
    ])
    def test_initialization_various_repetitions(self, max_rep, expected_total):
        """Test initialization with various repetition values using parameterization."""
        lst = FiniteCyclicList([1, 2, 3], max_repetitions=max_rep)

        assert len(lst) == expected_total
        assert lst.max_repetitions == max_rep
        assert lst._total_length == expected_total