#!/usr/bin/env bash
# Sign the split-tunnel domain list with the dedicated list key (not the update key).
# GOZAR_LIST_KEY_FILE=/secure/list-key.pem tools/sign-ir-domains.sh [list] [signature]
set -euo pipefail
umask 077

LIST="${1:-packaging/ir-domains.txt}"
OUT="${2:-${LIST}.sig}"
KEY="${GOZAR_LIST_KEY_FILE:?set GOZAR_LIST_KEY_FILE to the list private key pem}"

# Must equal ListKeyHex in go-desktop/internal/geo/listsig.go and FoxGeo.LIST_KEY_HEX.
EXPECTED_PUB="bd4c9be650ce400976e334c9d37a50fc03d9eddd72c2369e3ae0ca8669c97e88"
ACTUAL_PUB=$(openssl pkey -in "$KEY" -pubout -outform DER | tail -c 32 | od -An -tx1 | tr -d ' \n')
if [ "$ACTUAL_PUB" != "$EXPECTED_PUB" ]; then
    echo 'refusing to sign: the key is not the list key pinned in ListKeyHex' >&2
    exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# The prefix must match listSignaturePrefix in the Go verifier and FoxGeo.acceptSigned.
{ printf 'GOZAR-IR-DOMAINS-V1\n'; cat "$LIST"; } > "$WORK/message"
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$WORK/message" -out "$WORK/sig.bin"
if [ "$(wc -c < "$WORK/sig.bin")" -ne 64 ]; then
    echo 'refusing: unexpected Ed25519 signature length' >&2
    exit 1
fi
openssl pkey -in "$KEY" -pubout > "$WORK/pub.pem"
openssl pkeyutl -verify -rawin -pubin -inkey "$WORK/pub.pem" -in "$WORK/message" -sigfile "$WORK/sig.bin" >/dev/null
od -An -tx1 "$WORK/sig.bin" | tr -d ' \n' > "$WORK/sig.hex"
printf '\n' >> "$WORK/sig.hex"
mv "$WORK/sig.hex" "$OUT"
echo "signed $LIST -> $OUT"
