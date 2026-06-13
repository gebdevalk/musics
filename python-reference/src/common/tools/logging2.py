import logging
from functools import wraps


def log_calls(logger_name="app", level=logging.INFO):
    """
    Simple logging decorator with configurable logger and level.
    Default level is INFO.
    """
    logger = logging.getLogger(logger_name)

    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            logger.log(level, f"Calling {func.__name__} args={args}, kwargs={kwargs}")
            result = func(*args, **kwargs)
            logger.log(level, f"{func.__name__} returned {result}")
            return result

        return wrapper

    return decorator


# Example usage (defaults to INFO)
@log_calls()
def add(a, b):
    return a + b


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
    )

    result = add(3, 5)
    print("Result:", result)


if __name__ == "__main__":
    main()