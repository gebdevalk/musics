class Mapper:
    def __init__(self, input_range, output_range):
        self.input_min, self.input_max = input_range
        self.output_min, self.output_max = output_range
        self.output_type = type(self.output_min)

    def map(self, value):
        if value < self.input_min or value > self.input_max:
            raise ValueError(f"Value {value} outside range [{self.input_min}, {self.input_max}]")

        if self.input_max == self.input_min:
            mapped = self.output_min
        else:
            norm = (value - self.input_min) / (self.input_max - self.input_min)
            mapped = self.output_min + norm * (self.output_max - self.output_min)

        return int(round(mapped)) if self.output_type == int else float(mapped)


def main():
    # Example 1: Map integer to integer
    mapper1 = Mapper((0, 100), (0, 10))
    print(mapper1.map(50))  # Output: 5
    print(mapper1.map(0))   # Output: 0
    print(mapper1.map(100)) # Output: 10

    # Example 2: Map float to float
    mapper2 = Mapper((0.0, 1.0), (0.0, 100.0))
    print(mapper2.map(0.5)) # Output: 50.0
    print(mapper2.map(0.0)) # Output: 0.0
    print(mapper2.map(1.0)) # Output: 100.0

    # Example 3: Map integer to float
    mapper3 = Mapper((0, 360), (0.0, 2 * 3.14159))
    print(mapper3.map(180)) # Output: ~3.14159

    # Example 4: Map float to integer
    mapper4 = Mapper((0.0, 10.0), (0, 100))
    print(mapper4.map(5.0)) # Output: 50
    print(mapper4.map(3.7)) # Output: 37 (rounded)

    # Example 5: Degenerate input range
    mapper5 = Mapper((5, 5), (0, 100))
    print(mapper5.map(5)) # Output: 0

    # Error handling examples
    try:
        invalid_mapper = Mapper((10, 0), (0, 100)) # Invalid: input_min > input_max
    except ValueError as e:
        print(f"Error: {e}")

    try:
        mapper = Mapper((0, 100), (0, 10))
        mapper.map(150) # Invalid: value outside range
    except ValueError as e:
        print(f"Error: {e}")

    # Print mapper representation
    print(mapper1) # Output: Mapper(input_range=[0, 100], output_range=[0, 10])

if __name__ == "__main__":
    main()