from cyclic_list import T
from lisp.type_system import Type

def test_all(self):
    # Create type hierarchy (once, at startup)
    Object = Type(name="Object", has=[])
    Atom = Type(name="Atom", is_type=Object, has=None)
    Number = Type(name="Number", is_type=Atom, has=None)
    String = Type(name="String", is_type=Atom, has=None)
    List = Type(name="List", is_type=Object, has=[Atom])
    Matrix = Type(name="Matrix", is_type=List, has=[Number])

    # Create type factories
    Number = T("Number")
    String = T("String")
    List = T("List")
    Matrix = T("Matrix")

    # ============================================
    # DEMO: All Features in Action
    # ============================================

    print("="*60)
    print("1. BASIC LIST CREATION")
    print("="*60)

    # Different ways to create lists
    nums = Number(1, 2, 3, 4, 5)
    words = String(["hello", "world", "lisp"])
    singleton = Number >> 42
    matrix = Matrix([[1, 2, 3], [4, 5, 6], [7, 8, 9]])

    print(f"Number list: {nums}")
    print(f"String list: {words}")
    print(f"Singleton: {singleton}")
    print(f"Matrix:\n{matrix}")

    print("\n" + "="*60)
    print("2. AXIS-AWARE REVERSE")
    print("="*60)

    print(f"Original matrix:\n{matrix}")
    print(f"\nReverse rows (axis=0):\n{matrix.reverse(axis=0)}")
    print(f"\nReverse columns (axis=1):\n{matrix.reverse(axis=1)}")
    print(f"\nReverse both axes:\n{matrix.reverse(axis=(0, 1))}")
    print(f"\nReverse all axes:\n{matrix.reverse(axis=None)}")

    print("\n" + "="*60)
    print("3. MATHEMATICAL OPERATIONS")
    print("="*60)

    print(f"Original: {nums}")
    print(f"Negated: {-nums}")
    print(f"Absolute: {+(-nums)}")
    print(f"Squared: {nums**2}")
    print(f"Sum: {nums.sum}")
    print(f"Mean: {nums.mean}")
    print(f"Std dev: {nums.std:.2f}")

    print("\n" + "="*60)
    print("4. FILTERING OPERATIONS")
    print("="*60)

    print(f"Original: {nums}")
    print(f"Greater than 3: {nums > 3}")
    print(f"Less than 3: {nums < 3}")
    print(f"Equal to 3: {nums == 3}")

    print("\n" + "="*60)
    print("5. TRANSFORMATIONS")
    print("="*60)

    print(f"Original matrix:\n{matrix}")
    print(f"\nTranspose:\n{matrix.T}")
    print(f"\nFlattened:\n{matrix.flat}")
    print(f"\nReshaped (1,9):\n{matrix.reshape(1, 9)}")

    print("\n" + "="*60)
    print("6. LISP CLASSIC OPERATIONS")
    print("="*60)

    print(f"List: {nums}")
    print(f"car: {nums.car}")
    print(f"cdr: {nums.cdr}")
    print(f"First element via index: {nums[0]}")
    print(f"Slicing [1:4]: {nums[1:4]}")

    print("\n" + "="*60)
    print("7. NESTED STRUCTURES")
    print("="*60)

    nested = List([
        Number([1, 2, 3]),
        String(["a", "b", "c"]),
        Number([4, 5, 6])
    ])
    print(f"Nested structure:\n{nested}")

    # Operations on nested structures require custom handling
    # (NumPy works best with homogeneous numeric data)
    print("\n" + "="*60)
    print("8. TYPE INFORMATION")
    print("="*60)

    print(f"Number type: {Number.type}")
    print(f"Matrix type: {Matrix.type}")
    print(f"nums.type: {nums.type}")
    print(f"Is Number atomic? {Number.type.has is None}")
    print(f"Number parent: {Number.type.is_type.name}")

    print("\n" + "="*60)
    print("9. CHAINING OPERATIONS")
    print("="*60)

    result = ~(-matrix.T > 5) # Complex chain: transpose, negate, filter, reverse
    print(f"~(-matrix.T > 5):\n{result}")

    # Multi-step transformation
    transformed = matrix.T.flat.reshape(3, 3).reverse(axis=1)
    print(f"\nmatrix.T.flat.reshape(3,3).reverse(axis=1):\n{transformed}")

# Run the async event loop
if __name__ == "__main__":
    asyncio.run(main())
