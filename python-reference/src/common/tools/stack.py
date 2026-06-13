# common/tools/stack.py

class Stack:
    def __init__(self):
        """Initialize an empty stack"""
        self.items = []

    def push(self, item):
        """Add an item to the top of the stack"""
        self.items.append(item)

    def pop(self):
        """Remove and return the top item from the stack"""
        if self.is_empty():
            raise IndexError("Cannot pop from an empty stack")
        return self.items.pop()

    def peek(self):
        """Return the top item without removing it"""
        if self.is_empty():
            raise IndexError("Cannot peek into an empty stack")
        return self.items[-1]

    def is_empty(self):
        """Check if the stack is empty"""
        return len(self.items) == 0

    def size(self):
        """Return the number of items in the stack"""
        return len(self.items)

    def __str__(self):
        """String representation of the stack"""
        return str(self.items)

    def display(self):
        """Display the stack from bottom to top"""
        if self.is_empty():
            print("Stack is empty")
        else:
            for item in reversed(self.items):
                print(item)

    def clear(self):
        """Clear the stack"""
        self.items.clear()


# Example usage and testing
if __name__ == "__main__":
    # Create a new stack
    stack = Stack()

    # Test push operation
    print("=== Testing Push ===")
    stack.push(10)
    stack.push(20)
    stack.push(30)
    stack.display()
    print(f"Stack size: {stack.size()}")

    # Test peek operation
    print("\n=== Testing Peek ===")
    print(f"Top element: {stack.peek()}")
    stack.display()

    # Test pop operation
    print("\n=== Testing Pop ===")
    print(f"Popped: {stack.pop()}")
    print(f"Popped: {stack.pop()}")
    stack.display()
    print(f"Stack size: {stack.size()}")

    # Test peek after pops
    print("\n=== Testing After Pops ===")
    print(f"Top element: {stack.peek()}")

    # Test pop until empty
    print("\n=== Testing Empty Stack ===")
    print(f"Popped: {stack.pop()}")
    print(f"Is stack empty? {stack.is_empty()}")

    # Test error handling
    print("\n=== Testing Error Handling ===")
    try:
        stack.pop()
    except IndexError as e:
        print(f"Error caught: {e}")

    try:
        stack.peek()
    except IndexError as e:
        print(f"Error caught: {e}")