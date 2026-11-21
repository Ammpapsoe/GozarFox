#!/usr/bin/env python3
"""Record staged files without distributing them or inferring their licenses."""
import argparse
import csv
import hashlib
from pathlib import Path
import stat


def group(path):
    name = path.name.lower()
    parts = tuple(part.lower() for part in path.parts)
    if name in {'gozar-core', 'gozar-core.exe'}:
        return 'Gozar Go core'
    if name in {'gozar-original-ui', 'gozar-original-ui.exe'}:
        return 'Gozar Qt interface (mixed sources)'
    if any(part.startswith('qt') and part.endswith('.framework') for part in parts):
        return 'Qt framework'
    if ('qt' in parts or name.startswith(('qt6', 'libqt6')) or
            any(part in {'platforms', 'imageformats', 'iconengines', 'xcbglintegrations', 'platformthemes'} for part in parts)):
        return 'Qt deployment tree (may contain third-party components)'
    if 'flutter_assets' in parts or any('flutter' in part for part in parts):
        return 'Flutter-related payload'
    if name.startswith(('license', 'notice')) or 'license' in name or name.endswith('-ofl.txt'):
        return 'License or notice'
    return 'Unclassified; review actual origin'


def scan(root):
    pending = [root]
    while pending:
        directory = pending.pop()
        for item in sorted(directory.iterdir(), reverse=True):
            relative = item.relative_to(root)
            meta = item.lstat()
            if stat.S_ISLNK(meta.st_mode):
                # Include link text in evidence without traversing its target.
                target = item.readlink()
                if target.is_absolute() or not item.resolve(strict=False).is_relative_to(root):
                    raise SystemExit(f'Payload contains an escaping link: {relative}')
                yield (relative.as_posix(), 'symlink', '', '', str(target), group(relative))
            elif stat.S_ISDIR(meta.st_mode):
                pending.append(item)
            elif stat.S_ISREG(meta.st_mode):
                with item.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                yield (relative.as_posix(), 'file', meta.st_size, digest, '', group(relative))
            else:
                raise SystemExit(f'Unexpected staged entry type: {relative}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('staged_payload', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    root = args.staged_payload.resolve(strict=True)
    rows = sorted(scan(root))
    if not rows or not any(row[-1] == 'Gozar Qt interface (mixed sources)' and row[1] == 'file' for row in rows):
        raise SystemExit('No packaged Qt UI found')
    with args.report.open('w', encoding='utf-8', newline='') as report:
        writer = csv.writer(report)
        writer.writerow(('path', 'kind', 'bytes', 'sha256', 'link_target', 'origin_hint'))
        writer.writerows(rows)
    print(f'{len(rows)} staged entries inventoried; origin hints are not license decisions.')


if __name__ == '__main__':
    main()
