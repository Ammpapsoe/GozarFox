import os
import sys
import tarfile

app, destination = sys.argv[1:3]
with tarfile.open(destination, 'w:xz', preset=9) as archive:
    archive.add(app, arcname='Gozar.app')
size = os.path.getsize(destination)
if size >= 17_000_000:
    raise SystemExit(f'The macOS download exceeds the 17 MB limit: {size} bytes')
