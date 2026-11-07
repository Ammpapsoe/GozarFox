#!/usr/bin/env python3
"""Read an explicit release channel from the first line of release notes."""
import argparse
from pathlib import Path
import sys

MARKERS = {
    "<!-- STABLE -->": "stable",
    "<!-- PRERELEASE -->": "prerelease",
    "<!-- DRAFT -->": "draft",
}


def release_channel(path: Path, allow_draft: bool = False) -> str:
    with path.open(encoding="utf-8") as handle:
        first = handle.readline().strip()
    channel = MARKERS.get(first)
    if channel is None:
        raise ValueError("release notes must start with an explicit STABLE, PRERELEASE or DRAFT marker")
    if channel == "draft" and not allow_draft:
        raise ValueError("draft notes cannot be published")
    return channel


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--allow-draft", action="store_true")
    args = parser.parse_args()
    try:
        print(release_channel(args.path, args.allow_draft))
        return 0
    except (OSError, ValueError) as error:
        print(f"refusing release channel: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
