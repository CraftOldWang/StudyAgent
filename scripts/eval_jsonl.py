"""JSONL separates records by physical newlines; Unicode separators inside JSON strings are data."""
import json
from pathlib import Path


def read_jsonl(path):
    with Path(path).open(encoding="utf-8-sig") as stream:
        return [json.loads(line) for line in stream if line.strip()]
