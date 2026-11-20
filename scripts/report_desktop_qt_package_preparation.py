#!/usr/bin/env python3
"""Hash the internal packages and report blockers, without publishing binaries."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

packages, destination = map(Path, sys.argv[1:])
label = os.environ['PACKAGE_LABEL']
expected = {
    'Windows-x64': ('Gozar-Windows-x64-Setup.exe', 'Gozar-Windows-x64.zip'),
    'Linux-amd64': ('Gozar-Linux-amd64.deb', 'Gozar-Linux-amd64.tar.xz'),
    'macOS-Intel': ('Gozar-macOS-Intel.tar.xz',),
    'macOS-ARM': ('Gozar-macOS-ARM.tar.xz',),
}[label]
if sorted(p.name for p in packages.iterdir()) != sorted(expected):
    raise SystemExit('Unexpected archive coverage')
entries = []
for name in expected:
    binary = packages / name
    if binary.is_symlink() or not binary.is_file() or not binary.stat().st_size:
        raise SystemExit(f'Missing regular nonempty archive: {name}')
    with binary.open('rb') as stream:
        sha256 = hashlib.file_digest(stream, 'sha256').hexdigest()
    entries.append({'name': name, 'bytes': binary.stat().st_size, 'sha256': sha256})

root = Path(subprocess.check_output(('git', 'rev-parse', '--show-toplevel'), text=True).strip())
versions = {
    'flutter': re.search(r'^version:\s+(\d+\.\d+\.\d+)\+', (root / 'desktop_flutter/pubspec.yaml').read_text(), re.M)[1],
    'core': re.search(r'^const version = "(\d+\.\d+\.\d+)"', (root / 'go-desktop/cmd/gozar/main.go').read_text(), re.M)[1],
}
blockers = [
    'No distribution authorization decision recorded for Qt/Windscribe and complete runtime/asset notices.',
    'Official signing and production publication are not part of this validation workflow.',
    'Signed-out smoke is not an upgrade, real-account, or VPN lifecycle test.',
]
if set(versions.values()) != {'1.20.0'}:
    blockers.append('Component versions are unchanged; do not label the binaries 1.20.0.')
if any(entry['name'].endswith('Windows-x64.zip') and entry['bytes'] >= 20_000_000 for entry in entries):
    blockers.append('Windows archive is outside the existing 20 MB download budget.')
if label.startswith('macOS-') and entries[0]['bytes'] >= 17_000_000:
    blockers.append('macOS archive is outside the existing 17 MB download budget.')
report = {
    'source_commit': subprocess.check_output(('git', 'rev-parse', 'HEAD'), text=True).strip(),
    'platform': label,
    'status': 'validation-only',
    'publication_eligible': False,
    'component_versions': versions,
    'packages': entries,
    'blockers': blockers,
}
text = json.dumps(report, indent=2) + '\n'
destination.write_text(text, encoding='utf-8')
print(text)
