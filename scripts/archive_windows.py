import pathlib
import sys
import zipfile

source, destination = map(pathlib.Path, sys.argv[1:3])
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source))
# 20 MB since 1.14.1: the browser sign-in adds the WebView2 loader and the
# webview_windows plugin to the bundle, ~1 MB over the old 17 MB ceiling.
size = destination.stat().st_size
if size >= 20_000_000:
    raise SystemExit(f'Windows archive exceeds the 20 MB download limit: {size} bytes')
