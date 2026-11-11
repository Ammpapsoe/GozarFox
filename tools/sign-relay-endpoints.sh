#!/usr/bin/env bash
set -euo pipefail
umask 077
LIST="${1:-packaging/relay-endpoints.json}"
OUT="${2:-${LIST}.sig}"
KEY="${GOZAR_LIST_KEY_FILE:?set GOZAR_LIST_KEY_FILE to the list private key pem}"
EXPECTED_PUB="bd4c9be650ce400976e334c9d37a50fc03d9eddd72c2369e3ae0ca8669c97e88"
ACTUAL_PUB=$(openssl pkey -in "$KEY" -pubout -outform DER | tail -c 32 | od -An -tx1 | tr -d ' \n')
if [ "$ACTUAL_PUB" != "$EXPECTED_PUB" ]; then
    echo 'refusing to sign: key does not match the pinned relay discovery key' >&2
    exit 1
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
{ printf 'GOZAR-RELAY-ENDPOINTS-V1\n'; cat "$LIST"; } > "$WORK/message"
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$WORK/message" -out "$WORK/signature"
[ "$(wc -c < "$WORK/signature")" -eq 64 ]
openssl pkey -in "$KEY" -pubout > "$WORK/public.pem"
openssl pkeyutl -verify -rawin -pubin -inkey "$WORK/public.pem" -in "$WORK/message" -sigfile "$WORK/signature" >/dev/null
od -An -tx1 "$WORK/signature" | tr -d ' \n' > "$WORK/signature.hex"
printf '\n' >> "$WORK/signature.hex"
mv "$WORK/signature.hex" "$OUT"
