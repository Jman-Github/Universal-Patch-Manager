"""Compatibility entry point for the Liberica image smoke test."""
from pathlib import Path
from runpy import run_path

run_path(str(Path(__file__).with_name("embedded-jvm-image-test.py")), run_name="__main__")
