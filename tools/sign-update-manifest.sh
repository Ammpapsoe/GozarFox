#!/usr/bin/env bash
# Builds and signs the update.json release manifest for Gozar Android.
#
# Usage:
#   GOZAR_RELEASE_KEY=/path/to/release-private.pem \
#     tools/sign-update-manifest.sh <version> <channel_min> <apk-dir> [notes]
#
# Produces <apk-dir>/update.json and <apk-dir>/update.json.sig ready to be
# attached to the GitHub release next to the APKs. The private key never
# lives in the repository; the pinned public key is cross-checked before
# signing so a wrong key cannot produce an unusable manifest.
set -euo pipefail

VERSION="${1:?usage: sign-update-manifest.sh <version> <channel_min> <apk-dir> [notes]}"
CHANNEL_MIN="${2:?missing channel_min}"
APK_DIR="${3:?missing apk dir}"
NOTES="${4:-}"
KEY="${GOZAR_RELEASE_KEY:?set GOZAR_RELEASE_KEY to the release private key pem}"

EXPECTED_PUB="75ec9a23ecb173ddb9683e1c574515b3d4676453836804f327e86492896184e8"
ACTUAL_PUB=$(openssl pkey -in "$KEY" -pubout -outform DER | tail -c 32 | od -An -tx1 | tr -d ' \n')
if [ "$ACTUAL_PUB" != "$EXPECTED_PUB" ]; then
    echo "refusing to sign: the key does not match the public key pinned in FoxUpdate.KEY_HEX" >&2
    exit 1
fi

python3 - "$VERSION" "$CHANNEL_MIN" "$APK_DIR" "$NOTES" <<'PY'
import hashlib, json, os, sys
version, channel_min, apk_dir, notes = sys.argv[1:5]
artifacts = []
for name in sorted(os.listdir(apk_dir)):
    if not name.endswith(".apk"):
        continue
    path = os.path.join(apk_dir, name)
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    artifacts.append({
        "name": name,
        "sha256": digest.hexdigest(),
        "size": os.path.getsize(path),
        "url": "https://github.com/bardia049b-stack/Gozar/releases/download/v%s/%s" % (version, name),
    })
if not artifacts:
    raise SystemExit("no .apk files found in %s" % apk_dir)
import time
manifest = {
    "version": version,
    "channel_min": channel_min,
    "rollback": False,
    "issued_at": int(time.time()),
    "notes": notes,
    "artifacts": artifacts,
}
with open(os.path.join(apk_dir, "update.json"), "w", encoding="utf-8") as out:
    json.dump(manifest, out, ensure_ascii=False, separators=(",", ":"))
print("update.json written for %d artifact(s)" % len(artifacts))
PY

openssl pkeyutl -sign -rawin -inkey "$KEY" \
    -in "$APK_DIR/update.json" -out "$APK_DIR/update.json.sig.raw"
base64 -w0 "$APK_DIR/update.json.sig.raw" > "$APK_DIR/update.json.sig"
rm -f "$APK_DIR/update.json.sig.raw"
echo "update.json.sig written"
