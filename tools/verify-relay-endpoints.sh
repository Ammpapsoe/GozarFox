#!/usr/bin/env bash
set -euo pipefail
umask 077
LIST="${1:-packaging/relay-endpoints.json}"
SIGNATURE="${2:-${LIST}.sig}"
EXPECTED_PUB="bd4c9be650ce400976e334c9d37a50fc03d9eddd72c2369e3ae0ca8669c97e88"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
printf '%s' "302a300506032b6570032100$EXPECTED_PUB" | xxd -r -p > "$WORK/public.der"
xxd -r -p "$SIGNATURE" > "$WORK/signature"
[ "$(wc -c < "$WORK/signature")" -eq 64 ]
{ printf 'GOZAR-RELAY-ENDPOINTS-V1\n'; cat "$LIST"; } > "$WORK/message"
printf '%s\n' 'VERIFY_STAGE=DER_READY'
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$WORK/public.der" -in "$WORK/message" -sigfile "$WORK/signature" >/dev/null
printf '%s\n' 'VERIFY_STAGE=VERIFIED'
