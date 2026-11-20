#!/usr/bin/env python3
"""Inventory the generated development qrc's exact inputs, not a release SBOM.

Names and hashes are evidence of what the simulator resource map references;
license status and rights are deliberately not inferred from filenames. Qt,
Go, Flutter, compiled translations and native runtime dependencies are outside
this inventory. Never use this output as legal or publication approval.
"""
import argparse
import csv
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--qrc', type=Path, required=True)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repository = args.repository.resolve(strict=True)
    qrc = args.qrc.resolve(strict=True)
    root = ET.parse(qrc).getroot()
    if root.tag != 'RCC':
        raise ValueError('not a Qt resource map')
    rows, seen = [], set()
    for resource in root.findall('qresource'):
        prefix = resource.get('prefix', '/')
        if prefix not in ('/', '/gozar-fonts', '/gozar'):
            raise ValueError('unexpected resource prefix')
        for entry in resource:
            if entry.tag != 'file' or not entry.text:
                raise ValueError('unexpected resource entry')
            alias = entry.get('alias')
            if alias and prefix != '/':
                alias = prefix.lstrip('/') + '/' + alias
            if not alias or alias in seen:
                raise ValueError('missing or duplicate resource alias')
            seen.add(alias)
            path = Path(entry.text.strip()).resolve(strict=True)
            if not path.is_relative_to(repository) or not path.is_file():
                raise ValueError('resource is outside the repository or is not a file')
            digest = hashlib.sha256()
            with path.open('rb') as src:
                for chunk in iter(lambda: src.read(1024 * 1024), b''):
                    digest.update(chunk)
            rows.append((alias, path.relative_to(repository).as_posix(), path.stat().st_size,
                         digest.hexdigest(), 'NOT REVIEWED'))
    required = {'svg/LOGO.svg', 'resources/fonts/ibmplexsans.ttf',
                'gozar/app.png', 'gozar-fonts/vazirmatn_regular.ttf', 'gozar-fonts/VAZIRMATN-OFL.txt'}
    if not required.issubset(seen) or 'gif/windscribe_spinner.gif' in seen:
        raise ValueError('expected simulation resources are missing or unexpectedly present')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', encoding='utf-8', newline='') as out:
        writer = csv.writer(out)
        writer.writerow(('qt_alias', 'source_path', 'bytes', 'sha256', 'rights_status'))
        writer.writerows(sorted(rows))
    print(f'Inventoried {len(rows)} development resource inputs; rights not reviewed.')


if __name__ == '__main__':
    main()
