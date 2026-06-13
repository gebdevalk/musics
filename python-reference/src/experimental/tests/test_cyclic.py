import unittest
import sys
from experimental.utils.cyclic import CyclicList, cyclic_list_of


class TestCyclicList(unittest.TestCase):

    def setUp(self):
        """Set up test fixtures"""
        self.empty_list = CyclicList[int]([])
        self.single_item = CyclicList([42])
        self.basic_list = CyclicList([1, 2, 3, 4, 5])
        self.repeated_list = CyclicList([1, 2, 3], repetitions=2)
        self.string_list = CyclicList(['a', 'b', 'c'])

    def test_initialization(self):
        """Test various initialization methods"""
        # Empty list
        lst1 = CyclicList[int]()
        self.assertEqual(len(lst1), 0)
        self.assertEqual(lst1.repetitions, 0)

        # List from collection
        lst2 = CyclicList([1, 2, 3])
        self.assertEqual(len(lst2), 3)
        self.assertEqual(list(lst2), [1, 2, 3])

        # List with repetitions
        lst3 = CyclicList([1, 2], repetitions=3)
        self.assertEqual(lst3.repetitions, 3)

        # Capacity constructor
        lst4 = CyclicList[int](5)
        self.assertEqual(len(lst4), 5)

        # cyclic_list_of helper
        lst5 = cyclic_list_of(1, 2, 3, 4)
        self.assertEqual(len(lst5), 4)
        self.assertEqual(list(lst5), [1, 2, 3, 4])

    def test_length_property(self):
        """Test the length property that accounts for repetitions"""
        self.assertEqual(self.basic_list.length, 5)  # No repetitions
        self.assertEqual(self.repeated_list.length, 9)  # 3 + 3*2 = 9

        # Empty list
        self.assertEqual(self.empty_list.length, 0)

        # Single item with repetitions
        lst = CyclicList([42], repetitions=5)
        self.assertEqual(lst.length, 6)  # 1 + 1*5 = 6

    def test_basic_getitem(self):
        """Test basic indexing without cycling"""
        self.assertEqual(self.basic_list[0], 1)
        self.assertEqual(self.basic_list[2], 3)
        self.assertEqual(self.basic_list[4], 5)

        # Negative indexing
        self.assertEqual(self.basic_list[-1], 5)
        self.assertEqual(self.basic_list[-2], 4)

        # Single item list
        self.assertEqual(self.single_item[0], 42)
        self.assertEqual(self.single_item[-1], 42)

    def test_cyclic_getitem(self):
        """Test cyclic indexing with repetitions"""
        lst = CyclicList([1, 2, 3], repetitions=2)

        # Within original range
        self.assertEqual(lst[0], 1)
        self.assertEqual(lst[1], 2)
        self.assertEqual(lst[2], 3)

        # First repetition
        self.assertEqual(lst[3], 1)
        self.assertEqual(lst[4], 2)
        self.assertEqual(lst[5], 3)

        # Second repetition
        self.assertEqual(lst[6], 1)
        self.assertEqual(lst[7], 2)
        self.assertEqual(lst[8], 3)

        # Beyond repetitions (should still cycle)
        self.assertEqual(lst[9], 1)
        self.assertEqual(lst[10], 2)

    def test_cyclic_getitem_negative(self):
        """Test negative cyclic indexing"""
        lst = CyclicList([1, 2, 3], repetitions=2)

        self.assertEqual(lst[-1], 3)
        self.assertEqual(lst[-2], 2)
        self.assertEqual(lst[-3], 1)
        self.assertEqual(lst[-4], 3)  # Cycles back
        self.assertEqual(lst[-5], 2)
        self.assertEqual(lst[-6], 1)

    def test_setitem(self):
        """Test setting values with cyclic indexing"""
        lst = CyclicList([1, 2, 3, 4, 5])

        # Normal setting
        lst[1] = 99
        self.assertEqual(lst[1], 99)

        # Cyclic setting
        lst[7] = 42  # Should set index 2 (7 % 5 = 2)
        self.assertEqual(lst[2], 42)

        # Negative setting
        lst[-2] = 77  # Should set index 3
        self.assertEqual(lst[3], 77)

    def test_iteration(self):
        """Test iteration over cyclic list"""
        lst = CyclicList([1, 2, 3], repetitions=2)

        # Collect all items from iteration
        items = list(lst)
        expected = [1, 2, 3, 1, 2, 3, 1, 2, 3]  # 3 + 3*2 = 9 items
        self.assertEqual(items, expected)

        # Test iteration with empty list
        empty_items = list(self.empty_list)
        self.assertEqual(empty_items, [])

    def test_iterator_methods(self):
        """Test iterator methods"""
        lst = CyclicList([1, 2, 3])
        it = iter(lst)

        # Test has_next equivalent (by catching StopIteration)
        self.assertEqual(next(it), 1)
        self.assertEqual(next(it), 2)
        self.assertEqual(next(it), 3)

        # Should cycle
        self.assertEqual(next(it), 1)
        self.assertEqual(next(it), 2)

        # Test manual iteration with count
        count = 0
        for _ in it:
            count += 1
            if count > 10:
                break
        self.assertEqual(count, 11)  # 3 more from above + 8 = 11

    def test_map(self):
        """Test map function"""
        # Map with transformation
        result = self.basic_list.map(lambda x: x * 2)
        self.assertEqual(list(result), [2, 4, 6, 8, 10])
        self.assertEqual(result.repetitions, self.basic_list.repetitions)

        # Map to string
        result = self.basic_list.map(lambda x: str(x))
        self.assertEqual(list(result), ['1', '2', '3', '4', '5'])

        # Map empty list
        result = self.empty_list.map(lambda x: x * 2)
        self.assertEqual(list(result), [])

    def test_map_to(self):
        """Test map_to function"""
        destination = CyclicList[int](5)
        result = self.basic_list.map_to(destination, lambda x: x * 3)

        self.assertEqual(list(result), [3, 6, 9, 12, 15])
        self.assertIs(result, destination)  # Should return the destination

    def test_infinite(self):
        """Test infinite list functionality"""
        lst = CyclicList([1, 2, 3])
        lst.infinite()
        self.assertEqual(lst.repetitions, sys.maxsize)

        # Test infinited method
        infinite_copy = lst.infinited()
        self.assertEqual(infinite_copy.repetitions, sys.maxsize)
        self.assertEqual(list(infinite_copy._delegate), [1, 2, 3])

    # def test_infinite_list_class(self):
    #     """Test InfiniteList class"""
    #     lst = InfiniteList([1, 2, 3])
    #     self.assertEqual(lst.repetitions, sys.maxsize)
    #     self.assertEqual(list(lst), [1, 2, 3, 1, 2, 3, 1, 2, 3])  # Should cycle indefinitely

    def test_repeated(self):
        """Test repeated method"""
        lst = CyclicList([1, 2])
        repeated = lst.repeated(3)

        self.assertEqual(list(repeated), [1, 2, 1, 2])  # Original + 2 repetitions
        self.assertEqual(repeated.repetitions, 2)  # count - 1

    def test_reverse_and_shuffle(self):
        """Test reverse and shuffle operations"""
        # In-place reverse
        lst = CyclicList([1, 2, 3, 4, 5])
        lst.reverse()
        self.assertEqual(list(lst), [5, 4, 3, 2, 1])

        # Reversed copy
        lst = CyclicList([1, 2, 3])
        reversed_copy = lst.reversed()
        self.assertEqual(list(reversed_copy), [3, 2, 1])
        self.assertEqual(lst.repetitions, reversed_copy.repetitions)

        # Shuffle (just test that it doesn't error and length remains same)
        lst = CyclicList([1, 2, 3, 4, 5])
        original_items = list(lst)
        lst.shuffle()
        self.assertEqual(len(lst), 5)
        self.assertEqual(set(lst), set(original_items))

        # Shuffled copy
        shuffled_copy = lst.shuffled()
        self.assertEqual(len(shuffled_copy), 5)
        self.assertEqual(set(shuffled_copy), set(original_items))

    def test_shift_operations(self):
        """Test shift operations"""
        lst = CyclicList([1, 2, 3, 4, 5])

        # Shift left
        lst.shift_left(2)
        self.assertEqual(list(lst), [3, 4, 5, 1, 2])

        # Shift right
        lst.shift_right(1)
        self.assertEqual(list(lst), [2, 3, 4, 5, 1])

        # Shifted left copy
        lst = CyclicList([1, 2, 3, 4, 5])
        shifted = lst.shifted_left(3)
        self.assertEqual(list(shifted), [4, 5, 1, 2, 3])
        self.assertEqual(shifted.repetitions, lst.repetitions)

        # Shifted right copy
        shifted = lst.shifted_right(2)
        self.assertEqual(list(shifted), [4, 5, 1, 2, 3])

        # Shift with negative numbers
        shifted = lst.shifted_left(-1)  # Should be same as shift_right(1)
        self.assertEqual(list(shifted), [5, 1, 2, 3, 4])

        # Shift empty list
        empty = CyclicList([])
        empty.shift_left(5)  # Should not error
        self.assertEqual(list(empty), [])

    def test_list_operations(self):
        """Test standard list operations"""
        lst = CyclicList([1, 2, 3])

        # Append
        lst.append(4)
        self.assertEqual(list(lst), [1, 2, 3, 4])

        # Insert
        lst.insert(1, 99)
        self.assertEqual(list(lst), [1, 99, 2, 3, 4])

        # Remove
        lst.remove(99)
        self.assertEqual(list(lst), [1, 2, 3, 4])

        # Pop
        value = lst.pop()
        self.assertEqual(value, 4)
        self.assertEqual(list(lst), [1, 2, 3])

        # Extend
        lst.extend([5, 6])
        self.assertEqual(list(lst), [1, 2, 3, 5, 6])

        # Clear
        lst.clear()
        self.assertEqual(list(lst), [])

    def test_iteration_tracking(self):
        """Test that iteration count is tracked correctly"""
        lst = CyclicList([1, 2, 3], repetitions=2)

        # Initial iteration should be -1
        self.assertEqual(lst.iteration, -1)

        # Accessing elements should update iteration when wrapping
        _ = lst[0]  # iteration still -1
        self.assertEqual(lst.iteration, -1)

        _ = lst[2]  # still within first cycle
        self.assertEqual(lst.iteration, -1)

        _ = lst[3]  # This wraps to index 0, should increment iteration
        self.assertEqual(lst.iteration, 0)

        _ = lst[6]  # Another wrap
        self.assertEqual(lst.iteration, 1)

    def test_edge_cases(self):
        """Test edge cases"""
        # Single element list with repetitions
        lst = CyclicList([42], repetitions=3)
        self.assertEqual(lst.length, 4)
        self.assertEqual([lst[i] for i in range(4)], [42, 42, 42, 42])

        # Very large index
        large_index = 10 ** 6
        self.assertEqual(lst[large_index], 42)

        # Empty list access should raise IndexError
        with self.assertRaises(IndexError):
            _ = self.empty_list[0]

        # Setting on empty list should raise IndexError
        with self.assertRaises(IndexError):
            self.empty_list[0] = 42

    def test_string_representation(self):
        """Test string representations"""
        lst = CyclicList([1, 2, 3], repetitions=2)
        self.assertEqual(str(lst), "[1, 2, 3]2:")

        lst2 = CyclicList([1, 2, 3])
        self.assertEqual(str(lst2), "[1, 2, 3]")

        # Repr should be informative
        self.assertTrue(repr(lst2).startswith("CyclicList("))

    def test_type_generic(self):
        """Test with different types"""
        # String list
        str_list = CyclicList(['a', 'b', 'c'])
        self.assertEqual(str_list[3], 'a')

        # Mixed type list
        mixed = CyclicList([1, 'two', 3.0])
        self.assertEqual(mixed[0], 1)
        self.assertEqual(mixed[1], 'two')
        self.assertEqual(mixed[2], 3.0)

        # Boolean list
        bool_list = CyclicList([True, False])
        self.assertEqual(bool_list[2], True)

    def test_noop(self):
        """Test noop method (just ensure it exists and does nothing)"""
        self.basic_list.noop()  # Should not raise any exception


if __name__ == '__main__':
    unittest.main()