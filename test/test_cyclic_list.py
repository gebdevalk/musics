# test_cyclic_list.py
# import pytest
# from domain.cyclic_list import CyclicList
#
# @pytest.fixture
# def empty_list():
#     return CyclicList()
#
#
# @pytest.fixture
# def basic_list():
#     return CyclicList([1, 2, 3], times=3)
#
#
# @pytest.fixture
# def four_item_list():
#     return CyclicList([1, 2, 3, 4], times=3)
#
#
# def test_initialization():
#     """Test initialization scenarios."""
#     # Default
#     lst = CyclicList()
#     assert len(lst) == 0
#     assert lst.cycles == 1
#
#     # With initial list
#     lst = CyclicList([1, 2, 3])
#     assert list(lst) == [1, 2, 3]
#     assert lst.cycles == 1
#
#     # With custom times
#     lst = CyclicList([1, 2, 3], times=5)
#     assert lst.cycles == 5
#     assert len(lst) == 15
#
#     # Negative times not allowed
#     with pytest.raises(ValueError, match="times must be non-negative"):
#         CyclicList([1, 2, 3], times=-1)
#
#
# def test_getitem_valid_indices(basic_list):
#     """Test __getitem__ with valid positive indices."""
#     # Within first cycle
#     assert basic_list[0] == 1
#     assert basic_list[1] == 2
#     assert basic_list[2] == 3
#
#     # Beyond first cycle
#     assert basic_list[3] == 1  # Start of second cycle
#     assert basic_list[4] == 2
#     assert basic_list[5] == 3
#     assert basic_list[6] == 1  # Start of third cycle
#     assert basic_list[7] == 2
#     assert basic_list[8] == 3
#
#
# def test_getitem_out_of_bounds(basic_list):
#     """Test __getitem__ with index beyond total_length."""
#     with pytest.raises(IndexError, match="index out of bounds"):
#         _ = basic_list[9]
#
#     with pytest.raises(IndexError, match="index out of bounds"):
#         _ = basic_list[100]
#
#
# def test_getitem_negative_indices(basic_list):
#     """Test __getitem__ with negative indices."""
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         _ = basic_list[-1]
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         _ = basic_list[-5]
#
#
# def test_getitem_slice_valid(basic_list):
#     """Test slice getting with valid positive indices."""
#     # Basic slice
#     result = basic_list[1:4]
#     assert list(result) == [2, 3, 1]
#
#     # Slice with step
#     result = basic_list[0:8:2]
#     assert list(result) == [1, 3, 2, 1]
#
#     # Empty slice (beyond bounds)
#     result = basic_list[10:15]
#     assert list(result) == []
#
#
# def test_getitem_slice_negative(basic_list):
#     """Test slice getting with negative indices."""
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         _ = basic_list[-5:-1]
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         _ = basic_list[::-1]
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         _ = basic_list[-3:]
#
#
# def test_setitem_valid_indices(four_item_list):
#     """Test setting items with valid indices within base range."""
#     lst = four_item_list
#
#     # Set within base range (indices 0-3)
#     lst[0] = 10
#     lst[1] = 20
#     lst[2] = 30
#     lst[3] = 40
#
#     assert lst.domain == [10, 20, 30, 40]
#     assert list(lst) == [10, 20, 30, 40] * 3
#
#
# def test_setitem_out_of_bounds(four_item_list):
#     """Test setting items beyond base domain range."""
#     lst = four_item_list
#
#     with pytest.raises(IndexError, match="index out of bounds"):
#         lst[4] = 99
#
#     with pytest.raises(IndexError, match="index out of bounds"):
#         lst[10] = 99
#
#     # Base domain unchanged
#     assert lst.domain == [1, 2, 3, 4]
#
#
# def test_setitem_negative_indices(four_item_list):
#     """Test setting items with negative indices."""
#     lst = four_item_list
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         lst[-1] = 99
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         lst[-5] = 99
#
#
# def test_setitem_slice_valid(four_item_list):
#     """Test slice assignment with valid indices within base range."""
#     lst = four_item_list
#
#     # Slice within base
#     lst[1:4] = [20, 30, 40]
#     assert lst.domain == [1, 20, 30, 40]
#     assert list(lst) == [1, 20, 30, 40] * 3
#
#     # Slice with step
#     lst[0:4:2] = [10, 30]
#     assert lst.domain == [10, 20, 30, 40]
#
#
# def test_setitem_slice_single_value(four_item_list):
#     """Test slice assignment with single value."""
#     lst = four_item_list
#
#     lst[1:4] = 99
#     assert lst.domain == [1, 99, 99, 99]
#     assert list(lst) == [1, 99, 99, 99] * 3
#
#
# def test_setitem_slice_out_of_bounds(four_item_list):
#     """Test slice assignment beyond base range."""
#     lst = four_item_list
#
#     with pytest.raises(IndexError, match="index out of bounds"):
#         lst[2:6] = [30, 40, 50, 60]  # Indices 4,5 are out of bounds
#
#
# def test_setitem_slice_negative(four_item_list):
#     """Test slice assignment with negative indices."""
#     lst = four_item_list
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         lst[-3:-1] = [30, 40]
#
#     with pytest.raises(IndexError, match="negative indices not allowed"):
#         lst[-5:3] = [10, 20, 30]
#
#
# def test_setitem_slice_mismatched_length(four_item_list):
#     """Test slice assignment with mismatched length."""
#     lst = four_item_list
#
#     with pytest.raises(ValueError, match="attempt to assign sequence of size 2 to slice of size 3"):
#         lst[1:4] = [20, 30]  # Need 3 values, got 2
#
#
# def test_append_and_extend():
#     """Test growing the base domain."""
#     lst = CyclicList([1, 2], times=3)
#
#     # Append
#     lst.append(3)
#     assert lst.domain == [1, 2, 3]
#     assert len(lst) == 9
#     assert list(lst) == [1, 2, 3] * 3
#
#     # Extend
#     lst.extend([4, 5])
#     assert lst.domain == [1, 2, 3, 4, 5]
#     assert len(lst) == 15
#     assert list(lst) == [1, 2, 3, 4, 5] * 3
#
#
# def test_set_after_append():
#     """Test setting after growing base."""
#     lst = CyclicList([1, 2, 3], times=3)
#
#     # Can't set index 3 initially
#     with pytest.raises(IndexError, match="index out of bounds"):
#         lst[3] = 99
#
#     # After append, index 3 becomes available
#     lst.append(4)
#     lst[3] = 40
#     assert lst.domain[3] == 40
#     assert list(lst)[3] == 40
#     assert list(lst)[7] == 40  # Same position in second cycle
#
#
# def test_iteration(basic_list):
#     """Test iteration."""
#     assert list(basic_list) == [1, 2, 3] * 3
#
#     # Zero times
#     lst = CyclicList([1, 2], times=0)
#     assert list(lst) == []
#
#
# def test_properties(basic_list):
#     """Test properties."""
#     assert basic_list.cycles == 3
#     assert basic_list.base_length == 3
#
#     basic_list.append(4)
#     assert basic_list.base_length == 4
#     assert basic_list.cycles == 3
#
#
# def test_repr():
#     """Test string representation."""
#     lst = CyclicList([1, 2, 3], times=3)
#     assert repr(lst) == "CyclicList([1, 2, 3], times=3)"
#
#     lst = CyclicList([], times=2)
#     assert repr(lst) == "CyclicList([], times=2)"
#
#
# def test_empty_list(empty_list):
#     """Test operations on empty list."""
#     assert len(empty_list) == 0
#     assert empty_list[0] is None
#     assert list(empty_list[0:5]) == []
#
#     # Setting on empty list does nothing
#     empty_list[0] = 42
#     assert len(empty_list) == 0
#
#
# def test_cyclic_invariant():
#     """Test that all cycles remain identical."""
#     lst = CyclicList([1, 2, 3, 4], times=4)
#
#     # Modify base
#     lst[1] = 20
#     lst[2] = 30
#
#     # All cycles should be identical
#     all_elements = list(lst)
#     cycle_len = len(lst.domain)
#
#     for i in range(lst.times):
#         cycle = all_elements[i * cycle_len:(i + 1) * cycle_len]
#         assert cycle == lst.domain
#
#
# def test_edge_cases():
#     """Test edge cases."""
#     # Very large times
#     large = CyclicList([1], times=1000)
#     assert len(large) == 1000
#     assert large[999] == 1
#
#     # Zero times
#     zero = CyclicList([1, 2, 3], times=0)
#     assert len(zero) == 0
#     with pytest.raises(IndexError, match="index out of bounds"):
#         _ = zero[0]
#
#     # Single element
#     single = CyclicList([42], times=5)
#     assert single[4] == 42