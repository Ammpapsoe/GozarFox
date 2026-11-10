#!/usr/bin/env bash
set -euo pipefail
umask 077
LIST="${1:-packaging/relay-endpoints.json}"
SIGNATURE="${2:-${LIST}.sig}"
EXPECTED_PUB="5a74ff31ae5dff9923384ae85eb403cd26967c0f63fe78cf03e5471788aec446"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
printf '%s' "302a300506032b6570032100$EXPECTED_PUB" | xxd -r -p > "$WORK/public.der"
xxd -r -p "$SIGNATURE" > "$WORK/signature"
[ "$(wc -c < "$WORK/signature")" -eq 64 ]
{ printf 'GOZAR-RELAY-ENDPOINTS-V1\n'; cat "$LIST"; } > "$WORK/message"
openssl pkeyutl -verify -rawin -pubin -inform DER -inkey "$WORK/public.der" -in "$WORK/message" -sigfile "$WORK/signature" >/dev/null
