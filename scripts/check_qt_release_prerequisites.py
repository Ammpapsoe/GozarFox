#!/usr/bin/env python3
"""Fail closed before official Qt binaries are uploaded or signed."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--metadata-only', action='store_true', help='check version declarations without distribution approval')
args = parser.parse_args()
patterns = {
    'desktop_flutter/pubspec.yaml': r'^version:\s+([0-9]+\.[0-9]+\.[0-9]+)\+[0-9]+',
    'go-desktop/cmd/gozar/main.go': r'^const version = "([0-9]+\.[0-9]+\.[0-9]+)"',
    'app/build.gradle': r"^\s*versionName '([0-9]+\.[0-9]+\.[0-9]+)'",
}
versions = {path: re.search(pattern, (ROOT / path).read_text(encoding='utf-8'), re.M) for path, pattern in patterns.items()}
if any(match is None for match in versions.values()) or len({m[1] for m in versions.values()}) != 1:
    raise SystemExit('Android, Flutter and core versions must agree before Qt release packaging.')
version = next(iter(versions.values()))[1]
if args.metadata_only:
    print(version)
    raise SystemExit(0)

path = ROOT / 'packaging/qt-distribution-approval.json'
try:
    review = json.loads(path.read_text(encoding='utf-8'))
except FileNotFoundError:
    raise SystemExit('Qt distribution approval has not been recorded. Official package upload and signing are blocked.')
except (OSError, ValueError) as error:
    raise SystemExit(f'Invalid Qt distribution approval: {error}')
if (not isinstance(review, dict) or review.get('version') != version or
        review.get('decision') != 'approved' or
        any(not isinstance(review.get(key), str) or not review[key].strip()
            for key in ('review_reference', 'rights_for_bundled_code_and_assets', 'corresponding_source_plan'))):
    raise SystemExit('Qt approval must record the matching version, a review reference, rights basis and a corresponding source plan.')
print(f'Qt distribution review recorded for {version}. Native package checks are still required.')
