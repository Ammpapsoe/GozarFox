#!/usr/bin/env bash
set -euo pipefail
list="${1:-packaging/bridges.json}"
signature="${2:-$list.sig}"
key="${GOZAR_BRIDGE_KEY_FILE:?set GOZAR_BRIDGE_KEY_FILE to the bridge private key pem}"
expected=5560cebe849c94040a1a7af57232a64981f5884bd6ef058e1170d0a92d74319d
test -s "$list"
test "$(wc -c < "$list")" -le 1048576
actual=$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null | tail -c 32 | od -An -tx1 -v | tr -d ' \n')
test "$actual" = "$expected"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
openssl pkeyutl -sign -rawin -inkey "$key" -in "$list" -out "$work/signature.raw"
test "$(wc -c < "$work/signature.raw")" -eq 64
base64 -w0 "$work/signature.raw" > "$signature"
printf '\n' >> "$signature"
