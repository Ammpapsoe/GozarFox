#!/usr/bin/env python3
"""Enforce the exact Windows ZIP/macOS approvals, retaining all other ceilings."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
label = sys.argv[2]
# New limits only for the targets expressly approved. The original Build
# workflow has a separate 20 MB desktop output check for everything else.
expect = {
    'Windows-x64': {'Gozar-Windows-x64.zip': 45_000_000, 'Gozar-Windows-x64-Setup.exe': 20_000_000},
    'Linux-amd64': {'Gozar-Linux-amd64.deb': 20_000_000, 'Gozar-Linux-amd64.tar.xz': 20_000_000},
    'macOS-Intel': {'Gozar-macOS-Intel.tar.xz': 25_000_000},
    'macOS-ARM': {'Gozar-macOS-ARM.tar.xz': 25_000_000},
}[label]
if {p.name for p in root.iterdir()} != set(expect):
    raise SystemExit(f'Unexpected native package set for {label}.')
failed = False
for name, ceiling in expect.items():
    artifact = root / name
    if artifact.is_symlink() or not artifact.is_file():
        raise SystemExit(f'{name}: package is not a regular file')
    size = artifact.stat().st_size
    accepted = 0 < size < ceiling
    print(f'{name}: {size:,} bytes (limit {ceiling:,}) — {"ok" if accepted else "blocked"}')
    failed |= not accepted
raise SystemExit(int(failed))
