import argparse
import pathlib
import sys
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=pathlib.Path)
parser.add_argument('destination', type=pathlib.Path)
parser.add_argument('--record-over-budget', action='store_true')
parser.add_argument('--qt-desktop', action='store_true')
opts = parser.parse_args()
source, destination = opts.source, opts.destination
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source))
# 20 MB since 1.14.1: the browser sign-in adds the WebView2 loader and the
# webview_windows plugin to the bundle, ~1 MB over the old 17 MB ceiling.
size = destination.stat().st_size
limit_mb = 45 if opts.qt_desktop else 20
if size >= limit_mb * 1_000_000:
    message = f'Windows archive exceeds the {limit_mb} MB download limit: {size} bytes'
    if not opts.record_over_budget:
        raise SystemExit(message)
    print('Validation only: ' + message, file=sys.stderr)
