#!/usr/bin/env bash
set -euo pipefail
list="${1:-packaging/bridges.json}"
signature="${2:-$list.sig}"
test -s "$list"
test -s "$signature"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
python3 - "$work/public.der" "$work/signature.raw" "$signature" <<'PY'
import base64
import sys
public = bytes.fromhex("302a300506032b6570032100") + bytes.fromhex("5560cebe849c94040a1a7af57232a64981f5884bd6ef058e1170d0a92d74319d")
signature = base64.b64decode(open(sys.argv[3], "rb").read().strip(), validate=True)
if len(signature) != 64:
    raise SystemExit("invalid bridge signature length")
open(sys.argv[1], "wb").write(public)
open(sys.argv[2], "wb").write(signature)
PY
openssl pkeyutl -verify -pubin -inkey "$work/public.der" -keyform DER -rawin -in "$list" -sigfile "$work/signature.raw" >/dev/null
