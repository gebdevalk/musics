import logging
import functools

logger = logging.getLogger(__name__)

def log_call(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        logger.info(f"{func.__name__} args={args} kwargs={kwargs}")
        result = func(*args, **kwargs)
        logger.info(f"{func.__name__} → {result!r}")
        return result
    return wrapper