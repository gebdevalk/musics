from setuptools import setup, find_packages

setup(
    name="pymusics",
    version="0.1.0",
    packages=find_packages(),
    install_requires=[
        'mypy>=1.8.0',
    ],
    python_requires='>=3.12',
)
