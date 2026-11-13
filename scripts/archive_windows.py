import pathlib
import sys
import zipfile

source, destination = map(pathlib.Path, sys.argv[1:3])
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source))
if destination.stat().st_size >= 17_000_000:
    raise SystemExit('Windows archive exceeds the 17 MB download limit')
