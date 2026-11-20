#!/usr/bin/env python3
"""Fetch one published Gozar 1.19.0 core without rebuilding or unpacking other files."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
from urllib.request import urlopen
import zipfile

VERSION = 'v1.19.0'
BASE = f'https://github.com/Ammpapsoe/GozarFox/releases/download/{VERSION}'
PACKAGES = {
    'Windows-x64': ('Gozar-Windows-x64.zip', '3219eac603d7b0a7a244b77021043d5ebaa623ff7d2ae0bc56d250eae69a7d00', 'gozar-core.exe'),
    'Linux-x64': ('Gozar-Linux-amd64.tar.xz', 'b4921118da3b55990617140c55452f12280784574d77ccb612c28aad8f387fc1', 'bundle/gozar-core'),
    'macOS-Intel': ('Gozar-macOS-Intel.tar.xz', '8833f2faefcaed3d9c24a94816f8080a8ec8c244ff9575ebb05491d0dc474d26', 'Gozar.app/Contents/MacOS/gozar-core'),
    'macOS-ARM': ('Gozar-macOS-ARM.tar.xz', '9a9632e67cc2f617459df4f68c5239ce0f558e052033ba99bd7e4481c0827b8e', 'Gozar.app/Contents/MacOS/gozar-core'),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('platform', choices=PACKAGES)
    parser.add_argument('output', type=Path)
    parser.add_argument('--archive', type=Path, help='Use a previously downloaded official archive')
    args = parser.parse_args()
    filename, digest, member = PACKAGES[args.platform]
    if args.output.name != Path(member).name:
        parser.error(f'Output filename must be {Path(member).name}')
    with tempfile.TemporaryDirectory() as folder:
        archive = args.archive or (Path(folder) / filename)
        if args.archive is None:
            with urlopen(f'{BASE}/{filename}', timeout=60) as source, archive.open('wb') as target:
                shutil.copyfileobj(source, target)
        with archive.open('rb') as content:
            if hashlib.file_digest(content, 'sha256').hexdigest() != digest:
                raise SystemExit(f'SHA-256 mismatch in {filename}; refusing to extract')
        staged = Path(folder) / Path(member).name
        if archive.suffix == '.zip':
            with zipfile.ZipFile(archive) as z, z.open(member) as content, staged.open('wb') as target:
                shutil.copyfileobj(content, target)
        else:
            with tarfile.open(archive, 'r:xz') as tar:
                info = tar.getmember(member)
                if not info.isfile():
                    raise SystemExit('Published core is not a regular file')
                with tar.extractfile(info) as content, staged.open('wb') as target:
                    shutil.copyfileobj(content, target)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(staged, args.output)
        os.chmod(args.output, 0o755)
        print(f'Extracted unchanged {VERSION} {args.platform} core from verified {filename}')


if __name__ == '__main__':
    main()
