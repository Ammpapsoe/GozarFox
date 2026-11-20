import argparse
import os
import sys
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('app')
parser.add_argument('destination')
parser.add_argument('--record-over-budget', action='store_true')
opts = parser.parse_args()
app, destination = opts.app, opts.destination
with tarfile.open(destination, 'w:xz', preset=9) as archive:
    archive.add(app, arcname='Gozar.app')
size = os.path.getsize(destination)
if size >= 17_000_000:
    message = f'The macOS download exceeds the 17 MB limit: {size} bytes'
    if not opts.record_over_budget:
        raise SystemExit(message)
    print('Validation only: ' + message, file=sys.stderr)
